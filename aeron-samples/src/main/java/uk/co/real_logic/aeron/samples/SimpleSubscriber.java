/*
 * Copyright 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.samples;

import uk.co.real_logic.aeron.Aeron;
import uk.co.real_logic.aeron.FragmentAssemblyAdapter;
import uk.co.real_logic.aeron.Subscription;
import uk.co.real_logic.agrona.CloseHelper;
import uk.co.real_logic.aeron.common.BackoffIdleStrategy;
import uk.co.real_logic.aeron.common.IdleStrategy;
import uk.co.real_logic.aeron.common.concurrent.SigInt;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.DataHandler;
import uk.co.real_logic.aeron.driver.MediaDriver;

//import java.io.PrintStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Basic Aeron subscriber application which can receive fragmented messages
 */
public class SimpleSubscriber
{
    private static final int STREAM_ID = 10;
    private static final String CHANNEL = "udp://localhost:40123";
    private static final int FRAGMENT_COUNT_LIMIT = 10;

    public static void main(final String[] args) throws Exception
    {
        System.out.println("Subscribing to " + CHANNEL + " on stream Id " + STREAM_ID);

        // Create shared memory segments
        SamplesUtil.useSharedMemoryOnLinux();

        final MediaDriver driver = MediaDriver.launch();

        // Create a context for a client and specify callback methods when
        // a new connection starts (eventNewConnection)
        // a connection goes inactive (eventInactiveConnection)
        final Aeron.Context ctx = new Aeron.Context() /* Callback at new producer starts */
        	.newConnectionHandler(SimpleSubscriber::eventNewConnection)
        	.inactiveConnectionHandler(SimpleSubscriber::eventInactiveConnection);

        // dataHandler method is called for every new datagram received
        // When a message is completely reassembled, the delegate method 'printStringMessage' is called
        final FragmentAssemblyAdapter dataHandler = new FragmentAssemblyAdapter(reassembledStringMessage(STREAM_ID));

        final AtomicBoolean running = new AtomicBoolean(true);

        //Register a SIGINT handler
        SigInt.register(() -> running.set(false));

        // Create an Aeron instance with client provided context configuration and connect to media driver
        try (final Aeron aeron = Aeron.connect(ctx);
        		//Add a subscription to Aeron for a given channel and steam. Also,
        		// supply a dataHandler to be called when data arrives

        		final Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID, dataHandler))
        		{

        			final IdleStrategy idleStrategy = new BackoffIdleStrategy(
        					100, 10, TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MICROSECONDS.toNanos(100));

        		try
                {
        			//Try to read the data for both the subscribers
                    while (running.get())
                    {
                        final int fragmentsRead = subscription.poll(FRAGMENT_COUNT_LIMIT);
                        idleStrategy.idle(fragmentsRead);
                    }
                }
                catch (final Exception ex)
                {
                    ex.printStackTrace();
                }
            System.out.println("Shutting down...");
        }

        CloseHelper.quietClose(driver);
    }
    /**
     * Print the information for a new connection to stdout.
     *
     * @param channel           for the connection
     * @param streamId          for the stream
     * @param sessionId         for the connection publication
     * @param sourceInformation that is transport specific
     */
    public static void eventNewConnection(
        final String channel, final int streamId, final int sessionId, final String sourceInformation)
    {
        System.out.println(
            String.format(
                "new connection on %s streamId %x sessionId %x from %s",
                channel, streamId, sessionId, sourceInformation));
    }

    /**
     * This handler is called when connection goes inactive
     *
     * @param channel   for the connection
     * @param streamId  for the stream
     * @param sessionId for the connection publication
     */
    public static void eventInactiveConnection(final String channel, final int streamId, final int sessionId)
    {
        System.out.println(
            String.format(
                "inactive connection on %s streamId %d sessionId %x",
                channel, streamId, sessionId));
    }

    /**
     * Return a reusable, parameterized {@link DataHandler} that prints to stdout
     *
     * @param streamId to show when printing
     * @return subscription data handler function that prints the message contents
     */
    public static DataHandler reassembledStringMessage(final int streamId)
    {
        return (buffer, offset, length, header) ->
        {
            final byte[] data = new byte[length];
            buffer.getBytes(offset, data);

            System.out.println(
                String.format(
                    "message (%s) to stream %d from session %x term id %x term offset %d (%d@%d)",
                    new String(data), streamId, header.sessionId(), header.termId(), header.termOffset(), length, offset));

        };
    }

}

