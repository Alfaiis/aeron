/*
 * Copyright 2014 - 2017 Real Logic Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.aeron.archiver.workloads;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.archiver.Archiver;
import io.aeron.archiver.ArchiverThreadingMode;
import io.aeron.archiver.NoOpRecordingEventsListener;
import io.aeron.archiver.TestUtil;
import io.aeron.archiver.client.ArchiveProxy;
import io.aeron.archiver.client.RecordingEventsPoller;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.logbuffer.Header;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.BufferUtil;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.*;
import org.junit.rules.TestWatcher;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.aeron.archiver.ArchiverSystemTest.recordingUri;
import static io.aeron.archiver.ArchiverSystemTest.startChannelDrainingSubscription;
import static io.aeron.archiver.TestUtil.*;
import static junit.framework.TestCase.assertTrue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

@Ignore
public class ArchiveReplayLoadTest
{

    static final String CONTROL_URI = "aeron:udp?endpoint=127.0.0.1:54327";
    static final int CONTROL_STREAM_ID = 100;
    static final int TEST_DURATION_SEC = 30;

    private static final int TIMEOUT_MS = 5000;

    private static final String REPLAY_URI = "aeron:udp?endpoint=127.0.0.1:54326";
    private static final String PUBLISH_URI = "aeron:ipc";
    private static final int PUBLISH_STREAM_ID = 1;
    private static final int MAX_FRAGMENT_SIZE = 1024;
    private static final double MEGABYTE = 1024.0d * 1024.0d;
    private static final int MESSAGE_COUNT = 2000000;
    private final MediaDriver.Context driverCtx = new MediaDriver.Context();
    private final Archiver.Context archiverCtx = new Archiver.Context();
    private final UnsafeBuffer buffer =
        new UnsafeBuffer(BufferUtil.allocateDirectAligned(4096, FrameDescriptor.FRAME_ALIGNMENT));
    private final Random rnd = new Random();
    private final long seed = System.nanoTime();
    private final File archiveDir = TestUtil.makeTempDir();

    @Rule
    public final TestWatcher testWatcher = TestUtil.newWatcher(ArchiveReplayLoadTest.class, seed);

    private Aeron aeron;
    private Archiver archiver;
    private MediaDriver driver;
    private final long recordingId = 0;
    private long remaining;
    private int fragmentCount;
    private int[] fragmentLength;
    private long totalDataLength;
    private long totalRecordingLength;
    private long recorded;
    private volatile int lastTermId = -1;
    private Throwable trackerError;

    private long correlationId;
    private long joinPosition;
    private FragmentHandler validateFragmentHandler = this::validateFragment;

    @Before
    public void before() throws Exception
    {
        rnd.setSeed(seed);

        driverCtx
            .termBufferSparseFile(true)
            .threadingMode(ThreadingMode.DEDICATED)
            .useConcurrentCounterManager(true)
            .errorHandler(Throwable::printStackTrace)
            .dirsDeleteOnStart(true);

        driver = MediaDriver.launch(driverCtx);

        archiverCtx
            .archiveDir(archiveDir)
            .fileSyncLevel(0)
            .threadingMode(ArchiverThreadingMode.DEDICATED)
            .countersManager(driverCtx.countersManager())
            .errorHandler(driverCtx.errorHandler());

        archiver = Archiver.launch(archiverCtx);
        println("Archiver started, dir: " + archiverCtx.archiveDir().getAbsolutePath());
        aeron = Aeron.connect();
    }

    @After
    public void after() throws Exception
    {
        CloseHelper.close(aeron);
        CloseHelper.close(archiver);
        CloseHelper.close(driver);

        if (null != archiveDir)
        {
            IoUtil.delete(archiveDir, false);
        }

        driverCtx.deleteAeronDirectory();
    }

    @Test(timeout = 180000)
    public void replay() throws IOException, InterruptedException
    {
        try (Publication controlRequest = aeron.addPublication(
                archiverCtx.controlChannel(), archiverCtx.controlStreamId());
             Subscription recordingEvents = aeron.addSubscription(
                archiverCtx.recordingEventsChannel(), archiverCtx.recordingEventsStreamId()))
        {
            final ArchiveProxy archiveProxy = new ArchiveProxy(controlRequest);

            awaitPublicationIsConnected(controlRequest);
            awaitSubscriptionIsConnected(recordingEvents);
            println("Archive service connected");

            final Subscription controlResponse = aeron.addSubscription(CONTROL_URI, CONTROL_STREAM_ID);
            assertTrue(archiveProxy.connect(CONTROL_URI, CONTROL_STREAM_ID));
            awaitSubscriptionIsConnected(controlResponse);
            println("Client connected");

            final long startRecordingCorrelationId = this.correlationId++;
            final String recordingUri = recordingUri(PUBLISH_URI);
            waitFor(() -> archiveProxy.startRecording(recordingUri, PUBLISH_STREAM_ID, startRecordingCorrelationId));
            println("Recording requested");
            waitForOk(controlResponse, startRecordingCorrelationId);

            final Publication publication = aeron.addPublication(PUBLISH_URI, PUBLISH_STREAM_ID);
            awaitPublicationIsConnected(publication);
            startChannelDrainingSubscription(aeron, PUBLISH_URI, PUBLISH_STREAM_ID);

            final int messageCount = prepAndSendMessages(recordingEvents, publication);

            assertNull(trackerError);
            println("All data arrived");

            println("Request stop recording");
            final long requestStopCorrelationId = this.correlationId++;
            waitFor(() -> archiveProxy.stopRecording(recordingUri, PUBLISH_STREAM_ID, requestStopCorrelationId));
            waitForOk(controlResponse, requestStopCorrelationId);

            final long duration = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TEST_DURATION_SEC);
            int i = 0;

            while (System.currentTimeMillis() < duration)
            {
                final long start = System.currentTimeMillis();
                validateReplay(archiveProxy, messageCount);

                printScore(++i, System.currentTimeMillis() - start);
            }
        }
    }

    private void printScore(final int i, final long time)
    {
        final double rate = (totalRecordingLength * 1000.0 / time) / MEGABYTE;
        final double receivedMb = totalRecordingLength / MEGABYTE;
        System.out.printf("%d : received %.02f MB, replayed @ %.02f MB/s %n", i, receivedMb, rate);
    }

    private int prepAndSendMessages(final Subscription recordingEvents, final Publication publication)
        throws InterruptedException
    {
        final int messageCount = MESSAGE_COUNT;
        fragmentLength = new int[messageCount];
        for (int i = 0; i < messageCount; i++)
        {
            final int messageLength = 64 + rnd.nextInt(MAX_FRAGMENT_SIZE - 64) - DataHeaderFlyweight.HEADER_LENGTH;
            fragmentLength[i] = messageLength + DataHeaderFlyweight.HEADER_LENGTH;
            totalDataLength += fragmentLength[i];
        }

        final CountDownLatch waitForData = new CountDownLatch(1);
        System.out.printf("Sending %,d messages with a total length of %,d bytes %n", messageCount, totalDataLength);

        trackRecordingProgress(recordingEvents, waitForData);
        publishDataToRecorded(publication, messageCount);
        waitForData.await();

        return messageCount;
    }

    private void publishDataToRecorded(final Publication publication, final int messageCount)
    {
        final int positionBitsToShift = Integer.numberOfTrailingZeros(publication.termBufferLength());
        joinPosition = publication.position();
        final int initialTermOffset = LogBufferDescriptor.computeTermOffsetFromPosition(
            joinPosition, positionBitsToShift);

        buffer.setMemory(0, 1024, (byte)'z');
        buffer.putStringAscii(32, "TEST");

        for (int i = 0; i < messageCount; i++)
        {
            final int dataLength = fragmentLength[i] - DataHeaderFlyweight.HEADER_LENGTH;
            buffer.putInt(0, i);
            printf("Sending: index=%d length=%d %n", i, dataLength);
            offer(publication, buffer, dataLength);
        }

        final int lastTermOffset = LogBufferDescriptor.computeTermOffsetFromPosition(
            publication.position(), positionBitsToShift);
        final int termIdFromPosition = LogBufferDescriptor.computeTermIdFromPosition(
            publication.position(), positionBitsToShift, publication.initialTermId());
        totalRecordingLength =
            (termIdFromPosition - publication.initialTermId()) * publication.termBufferLength() +
                (lastTermOffset - initialTermOffset);

        assertThat(publication.position() - joinPosition, is(totalRecordingLength));
        lastTermId = termIdFromPosition;
    }

    private void validateReplay(final ArchiveProxy archiveProxy, final int messageCount)
    {
        final int replayStreamId = (int)correlationId;

        try (Subscription replay = aeron.addSubscription(REPLAY_URI, replayStreamId))
        {
            final long correlationId = this.correlationId++;

            TestUtil.waitFor(() -> archiveProxy.replay(
                recordingId,
                joinPosition,
                totalRecordingLength,
                REPLAY_URI,
                replayStreamId,
                correlationId));

            awaitSubscriptionIsConnected(replay);

            fragmentCount = 0;
            remaining = totalDataLength;

            while (fragmentCount < messageCount && remaining > 0 && !replay.isClosed() && !replay.hasNoImages())
            {
                replay.poll(validateFragmentHandler, 128);
            }

            assertThat(fragmentCount, is(messageCount));
            assertThat(remaining, is(0L));
        }
    }

    private void validateFragment(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        @SuppressWarnings("unused") final Header header)
    {
        assertThat(length, is(fragmentLength[fragmentCount] - DataHeaderFlyweight.HEADER_LENGTH));
        assertThat(buffer.getInt(offset), is(fragmentCount));
        assertThat(buffer.getByte(offset + 4), is((byte)'z'));
        remaining -= fragmentLength[fragmentCount];
        fragmentCount++;
    }

    private void trackRecordingProgress(final Subscription recordingEvents, final CountDownLatch waitForData)
    {
        final RecordingEventsPoller recordingEventsPoller = new RecordingEventsPoller(
            new NoOpRecordingEventsListener()
            {
                public void onProgress(
                    final long recordingId0,
                    final long joinPosition,
                    final long position)
                {
                    assertThat(recordingId0, is(recordingId));
                    recorded = position - joinPosition;
                    printf("a=%d total=%d %n", recorded, totalRecordingLength);
                }
            },
            recordingEvents,
            1);

        final Thread t = new Thread(
            () ->
            {
                try
                {
                    recorded = 0;
                    long start = System.currentTimeMillis();
                    long startBytes = remaining;

                    while (lastTermId == -1 || recorded < totalRecordingLength)
                    {
                        TestUtil.waitFor(() -> recordingEventsPoller.poll() != 0);

                        final long end = System.currentTimeMillis();
                        final long deltaTime = end - start;
                        if (deltaTime > TIMEOUT_MS)
                        {
                            start = end;
                            final long deltaBytes = remaining - startBytes;
                            startBytes = remaining;
                            final double rate = ((deltaBytes * 1000.0) / deltaTime) / MEGABYTE;
                            printf("Archive reported rate: %.02f MB/s %n", rate);
                        }
                    }

                    final long deltaTime = System.currentTimeMillis() - start;
                    final long deltaBytes = remaining - startBytes;
                    final double rate = ((deltaBytes * 1000.0) / deltaTime) / MEGABYTE;
                    printf("Archive reported rate: %.02f MB/s %n", rate);
                }
                catch (final Throwable throwable)
                {
                    trackerError = throwable;
                }

                waitForData.countDown();
            });

        t.setDaemon(true);
        t.start();
    }
}
