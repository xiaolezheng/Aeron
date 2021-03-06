/*
 * Copyright 2014 - 2016 Real Logic Ltd.
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
package io.aeron.driver;

import io.aeron.driver.buffer.RawLog;
import io.aeron.driver.media.ReceiveChannelEndpoint;
import io.aeron.driver.status.SystemCounters;
import io.aeron.logbuffer.TermRebuilder;
import io.aeron.protocol.DataHeaderFlyweight;
import io.aeron.protocol.RttMeasurementFlyweight;
import org.agrona.UnsafeAccess;
import org.agrona.collections.ArrayUtil;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.NanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.Position;
import org.agrona.concurrent.status.ReadablePosition;

import java.net.InetSocketAddress;
import java.util.List;

import static io.aeron.driver.LossDetector.rebuildOffset;
import static io.aeron.driver.LossDetector.workCount;
import static io.aeron.driver.PublicationImage.Status.ACTIVE;
import static io.aeron.driver.status.SystemCounterDescriptor.*;
import static io.aeron.logbuffer.LogBufferDescriptor.*;

class PublicationImagePadding1
{
    @SuppressWarnings("unused")
    protected long p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15;
}

class PublicationImageConductorFields extends PublicationImagePadding1
{
    protected long cleanPosition;
}

class PublicationImagePadding2 extends PublicationImageConductorFields
{
    @SuppressWarnings("unused")
    protected long p16, p17, p18, p19, p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p30;
}

class PublicationImageHotFields extends PublicationImagePadding2
{
    protected long lastPacketTimestamp;
    protected long lastStatusMessageTimestamp;
    protected long lastStatusMessagePosition;
}

class PublicationImagePadding3 extends PublicationImageHotFields
{
    @SuppressWarnings("unused")
    protected long p31, p32, p33, p34, p35, p36, p37, p38, p39, p40, p41, p42, p43, p44, p45;
}

class PublicationImageStatusFields extends PublicationImagePadding3
{
    protected volatile long newStatusMessagePosition;
}

class PublicationImagePadding4 extends PublicationImageStatusFields
{
    @SuppressWarnings("unused")
    protected long p46, p47, p48, p49, p50, p51, p52, p53, p54, p55, p56, p57, p58, p59, p60;
}

/**
 * State maintained for active sessionIds within a channel for receiver processing
 */
public class PublicationImage
    extends PublicationImagePadding4
    implements GapHandler, DriverManagedResource
{
    enum Status
    {
        INIT, ACTIVE, INACTIVE, LINGER
    }

    private long timeOfLastStatusChange;
    private long lastChangeNumber = -1;

    private volatile long beginLossChange = -1;
    private volatile long endLossChange = -1;
    private int lossTermId;
    private int lossTermOffset;
    private int lossLength;

    private final long correlationId;
    private final long imageLivenessTimeoutNs;
    private final int sessionId;
    private final int streamId;
    private final int positionBitsToShift;
    private final int termLengthMask;
    private final int initialTermId;
    private final int currentWindowLength;
    private final int currentGain;
    private boolean reachedEndOfLife = false;

    private volatile PublicationImage.Status status = PublicationImage.Status.INIT;

    private final RawLog rawLog;
    private final InetSocketAddress controlAddress;
    private final InetSocketAddress sourceAddress;
    private final ReceiveChannelEndpoint channelEndpoint;
    private final NanoClock clock;
    private final UnsafeBuffer[] termBuffers;
    private final Position hwmPosition;
    private final Position rebuildPosition;
    private ReadablePosition[] subscriberPositions;
    private final LossDetector lossDetector;
    private final AtomicCounter heartbeatsReceived;
    private final AtomicCounter statusMessagesSent;
    private final AtomicCounter nakMessagesSent;
    private final AtomicCounter flowControlUnderRuns;
    private final AtomicCounter flowControlOverRuns;

    public PublicationImage(
        final long correlationId,
        final long imageLivenessTimeoutNs,
        final ReceiveChannelEndpoint channelEndpoint,
        final InetSocketAddress controlAddress,
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final int activeTermId,
        final int initialTermOffset,
        final int initialWindowLength,
        final RawLog rawLog,
        final FeedbackDelayGenerator lossFeedbackDelayGenerator,
        final List<ReadablePosition> subscriberPositions,
        final Position hwmPosition,
        final Position rebuildPosition,
        final NanoClock clock,
        final SystemCounters systemCounters,
        final InetSocketAddress sourceAddress)
    {
        this.correlationId = correlationId;
        this.imageLivenessTimeoutNs = imageLivenessTimeoutNs;
        this.channelEndpoint = channelEndpoint;
        this.controlAddress = controlAddress;
        this.sessionId = sessionId;
        this.streamId = streamId;
        this.rawLog = rawLog;
        this.subscriberPositions = subscriberPositions.toArray(new ReadablePosition[0]);
        this.hwmPosition = hwmPosition;
        this.rebuildPosition = rebuildPosition;
        this.sourceAddress = sourceAddress;
        this.initialTermId = initialTermId;

        heartbeatsReceived = systemCounters.get(HEARTBEATS_RECEIVED);
        statusMessagesSent = systemCounters.get(STATUS_MESSAGES_SENT);
        nakMessagesSent = systemCounters.get(NAK_MESSAGES_SENT);
        flowControlUnderRuns = systemCounters.get(FLOW_CONTROL_UNDER_RUNS);
        flowControlOverRuns = systemCounters.get(FLOW_CONTROL_OVER_RUNS);

        final long time = clock.nanoTime();
        this.clock = clock;
        timeOfLastStatusChange = time;
        lastPacketTimestamp = time;

        termBuffers = rawLog.termBuffers();
        lossDetector = new LossDetector(lossFeedbackDelayGenerator, this);

        final int termLength = rawLog.termLength();
        currentWindowLength = Math.min(termLength / 2, initialWindowLength);
        currentGain = currentWindowLength / 4;
        termLengthMask = termLength - 1;
        positionBitsToShift = Integer.numberOfTrailingZeros(termLength);

        final long initialPosition = computePosition(activeTermId, initialTermOffset, positionBitsToShift, initialTermId);
        lastStatusMessagePosition = initialPosition - (currentGain + 1);
        newStatusMessagePosition = lastStatusMessagePosition;
        cleanPosition = initialPosition;

        hwmPosition.setOrdered(initialPosition);
        rebuildPosition.setOrdered(initialPosition);
    }

    /**
     * {@inheritDoc}
     */
    public void close()
    {
        hwmPosition.close();
        rebuildPosition.close();
        for (final ReadablePosition position : subscriberPositions)
        {
            position.close();
        }

        rawLog.close();
    }

    public long correlationId()
    {
        return correlationId;
    }

    /**
     * The session id of the channel from a publisher.
     *
     * @return session id of the channel from a publisher.
     */
    public int sessionId()
    {
        return sessionId;
    }

    /**
     * The stream id of this image within a channel.
     *
     * @return stream id of this image within a channel.
     */
    public int streamId()
    {
        return streamId;
    }

    /**
     * Get the string representation of the channel URI.
     *
     * @return the string representation of the channel URI.
     */
    public String channelUriString()
    {
        return channelEndpoint.originalUriString();
    }

    /**
     * The address of the source associated with the image.
     *
     * @return source address
     */
    InetSocketAddress sourceAddress()
    {
        return sourceAddress;
    }

    /**
     * Return the {@link ReceiveChannelEndpoint} that the image is attached to.
     *
     * @return {@link ReceiveChannelEndpoint} that the image is attached to.
     */
    ReceiveChannelEndpoint channelEndpoint()
    {
        return channelEndpoint;
    }

    /**
     * Remove this image from the {@link DataPacketDispatcher} so it will process no further packets from the network.
     * Called from the {@link Receiver} thread.
     */
    void removeFromDispatcher()
    {
        channelEndpoint.removePublicationImage(this);
    }

    /**
     * Does this image match a given {@link ReceiveChannelEndpoint} and stream id?
     *
     * @param channelEndpoint to match by identity.
     * @param streamId        to match on value.
     * @return true on a match otherwise false.
     */
    public boolean matches(final ReceiveChannelEndpoint channelEndpoint, final int streamId)
    {
        return this.streamId == streamId && this.channelEndpoint == channelEndpoint;
    }

    /**
     * Get the {@link RawLog} the back this image.
     *
     * @return the {@link RawLog} the back this image.
     */
    RawLog rawLog()
    {
        return rawLog;
    }

    /**
     * Return status of the image. Retrieved by {@link DriverConductor}.
     *
     * @return status of the image
     */
    public Status status()
    {
        return status;
    }

    /**
     * Set status of the image.
     *
     * Set by {@link Receiver} for INIT to ACTIVE to INACTIVE
     *
     * Set by {@link DriverConductor} for INACTIVE to LINGER
     *
     * @param status of the image
     */
    public void status(final Status status)
    {
        timeOfLastStatusChange = clock.nanoTime();
        this.status = status;
    }

    /**
     * Set status to INACTIVE, but only if currently ACTIVE. Set by {@link Receiver}.
     */
    void ifActiveGoInactive()
    {
        if (Status.ACTIVE == status)
        {
            status(Status.INACTIVE);
        }
    }

    /**
     * Called from the {@link LossDetector} when gap is detected from the {@link DriverConductor} thread.
     *
     * @see GapHandler
     */
    public void onLossDetected(final int termId, final int termOffset, final int length)
    {
        final long changeNumber = beginLossChange + 1;

        beginLossChange = changeNumber;

        lossTermId = termId;
        lossTermOffset = termOffset;
        lossLength = length;

        endLossChange = changeNumber;
    }

    /**
     * Called from the {@link DriverConductor}.
     *
     * @param now in nanoseconds
     * @return if work has been done or not
     */
    int trackRebuild(final long now)
    {
        long minSubscriberPosition = Long.MAX_VALUE;
        long maxSubscriberPosition = Long.MIN_VALUE;

        for (final ReadablePosition subscriberPosition : subscriberPositions)
        {
            final long position = subscriberPosition.getVolatile();
            minSubscriberPosition = Math.min(minSubscriberPosition, position);
            maxSubscriberPosition = Math.max(maxSubscriberPosition, position);
        }

        if (minSubscriberPosition > (newStatusMessagePosition + currentGain))
        {
            newStatusMessagePosition = minSubscriberPosition;
            cleanBufferTo(minSubscriberPosition - (termLengthMask + 1));
        }

        final long rebuildPosition = Math.max(this.rebuildPosition.get(), maxSubscriberPosition);

        final long scanOutcome = lossDetector.scan(
            termBuffers[indexByPosition(rebuildPosition, positionBitsToShift)],
            rebuildPosition,
            hwmPosition.getVolatile(),
            now,
            termLengthMask,
            positionBitsToShift,
            initialTermId);

        final int rebuildTermOffset = (int)rebuildPosition & termLengthMask;
        final long newRebuildPosition = (rebuildPosition - rebuildTermOffset) + rebuildOffset(scanOutcome);
        this.rebuildPosition.proposeMaxOrdered(newRebuildPosition);

        return workCount(scanOutcome);
    }

    /**
     * Insert frame into term buffer.
     *
     * @param termId     for the data packet to insert into the appropriate term.
     * @param termOffset for the start of the packet in the term.
     * @param buffer     for the data packet to insert into the appropriate term.
     * @param length     of the data packet
     * @return number of bytes applied as a result of this insertion.
     */
    int insertPacket(final int termId, final int termOffset, final UnsafeBuffer buffer, final int length)
    {
        final boolean isHeartbeat = isHeartbeat(buffer, length);
        final long packetPosition = computePosition(termId, termOffset, positionBitsToShift, initialTermId);
        final long proposedPosition = isHeartbeat ? packetPosition : packetPosition + length;
        final long windowPosition = lastStatusMessagePosition;

        if (!isFlowControlUnderRun(windowPosition, packetPosition) &&
            !isFlowControlOverRun(windowPosition, proposedPosition))
        {
            if (isHeartbeat)
            {
                heartbeatsReceived.orderedIncrement();
            }
            else
            {
                final UnsafeBuffer termBuffer = termBuffers[indexByPosition(packetPosition, positionBitsToShift)];
                TermRebuilder.insert(termBuffer, termOffset, buffer, length);
            }

            hwmCandidate(proposedPosition);
        }

        return length;
    }

    /**
     * To be called from the {@link Receiver} to see if a image should be garbage collected.
     *
     * @param now current time to check against.
     * @return true if still active otherwise false.
     */
    boolean checkForActivity(final long now)
    {
        boolean activity = true;

        if (now > (lastPacketTimestamp + imageLivenessTimeoutNs))
        {
            activity = false;
        }

        return activity;
    }

    /**
     * Called from the {@link Receiver} to send any pending Status Messages.
     *
     * @param now                  time in nanoseconds.
     * @param statusMessageTimeout for sending of Status Messages.
     * @return number of work items processed.
     */
    int sendPendingStatusMessage(final long now, final long statusMessageTimeout)
    {
        int workCount = 0;

        if (ACTIVE == status)
        {
            final long statusMessagePosition = newStatusMessagePosition;
            if (statusMessagePosition != lastStatusMessagePosition || now > (lastStatusMessageTimestamp + statusMessageTimeout))
            {
                final int termId = computeTermIdFromPosition(statusMessagePosition, positionBitsToShift, initialTermId);
                final int termOffset = (int)statusMessagePosition & termLengthMask;

                channelEndpoint.sendStatusMessage(
                    controlAddress, sessionId, streamId, termId, termOffset, currentWindowLength, (byte)0);

                lastStatusMessageTimestamp = now;
                lastStatusMessagePosition = statusMessagePosition;
                statusMessagesSent.orderedIncrement();
                workCount = 1;
            }
        }

        return workCount;
    }

    /**
     * Called from the {@link Receiver} thread to processing any pending loss of packets.
     *
     * @return number of work items processed.
     */
    int processPendingLoss()
    {
        int workCount = 0;
        final long changeNumber = endLossChange;

        if (changeNumber != lastChangeNumber)
        {
            final int termId = lossTermId;
            final int termOffset = lossTermOffset;
            final int length = lossLength;

            UnsafeAccess.UNSAFE.loadFence(); // LoadLoad required so previous loads don't move past version check below.

            if (changeNumber == beginLossChange)
            {
                channelEndpoint.sendNakMessage(controlAddress, sessionId, streamId, termId, termOffset, length);
                lastChangeNumber = changeNumber;
                nakMessagesSent.orderedIncrement();
                workCount = 1;
            }
        }

        return workCount;
    }

    /**
     * Called from the {@link Receiver} upon receiving an RTT Measurement that is a reply.
     *
     * @param header     of the measurement
     * @param srcAddress from the sender of the measurement
     */
    public void onRttMeasurement(final RttMeasurementFlyweight header, final InetSocketAddress srcAddress)
    {
        // TODO: hook into congestion control strategy
    }

    /**
     * Remove a {@link ReadablePosition} for a subscriber that has been removed so it is not tracked for flow control.
     *
     * @param subscriberPosition for the subscriber that has been removed.
     */
    void removeSubscriber(final ReadablePosition subscriberPosition)
    {
        subscriberPositions = ArrayUtil.remove(subscriberPositions, subscriberPosition);
        subscriberPosition.close();
    }

    /**
     * Add a new subscriber to this image so their position can be tracked for flow control.
     *
     * @param subscriberPosition for the subscriber to be added.
     */
    void addSubscriber(final ReadablePosition subscriberPosition)
    {
        subscriberPositions = ArrayUtil.add(subscriberPositions, subscriberPosition);
    }

    /**
     * Return number of subscribers to this image.
     *
     * @return number of subscribers
     */
    int subscriberCount()
    {
        return subscriberPositions.length;
    }

    /**
     * The position up to which the current stream rebuild is complete for reception.
     *
     * @return the position up to which the current stream rebuild is complete for reception.
     */
    long rebuildPosition()
    {
        return rebuildPosition.get();
    }

    public void onTimeEvent(final long time, final DriverConductor conductor)
    {
        switch (status)
        {
            case INACTIVE:
                if (isDrained() || time > (timeOfLastStatusChange + imageLivenessTimeoutNs))
                {
                    status(PublicationImage.Status.LINGER);
                    conductor.imageTransitionToLinger(this);
                }
                break;

            case LINGER:
                if (time > (timeOfLastStatusChange + imageLivenessTimeoutNs))
                {
                    reachedEndOfLife = true;
                    conductor.cleanupImage(this);
                }
                break;
        }
    }

    public boolean hasReachedEndOfLife()
    {
        return reachedEndOfLife;
    }

    public void timeOfLastStateChange(final long time)
    {
    }

    public long timeOfLastStateChange()
    {
        return timeOfLastStatusChange;
    }

    public void delete()
    {
        close();
    }

    private boolean isDrained()
    {
        long minSubscriberPosition = Long.MAX_VALUE;

        for (final ReadablePosition subscriberPosition : subscriberPositions)
        {
            minSubscriberPosition = Math.min(minSubscriberPosition, subscriberPosition.getVolatile());
        }

        return minSubscriberPosition >= rebuildPosition.get();
    }

    private boolean isHeartbeat(final UnsafeBuffer packet, final int length)
    {
        return length == DataHeaderFlyweight.HEADER_LENGTH && packet.getInt(0) == 0;
    }

    private void hwmCandidate(final long proposedPosition)
    {
        lastPacketTimestamp = clock.nanoTime();
        hwmPosition.proposeMaxOrdered(proposedPosition);
    }

    private boolean isFlowControlUnderRun(final long windowPosition, final long packetPosition)
    {
        final boolean isFlowControlUnderRun = packetPosition < windowPosition;

        if (isFlowControlUnderRun)
        {
            flowControlUnderRuns.orderedIncrement();
        }

        return isFlowControlUnderRun;
    }

    private boolean isFlowControlOverRun(final long windowPosition, final long proposedPosition)
    {
        final boolean isFlowControlOverRun = proposedPosition > (windowPosition + currentWindowLength);

        if (isFlowControlOverRun)
        {
            flowControlOverRuns.orderedIncrement();
        }

        return isFlowControlOverRun;
    }

    private void cleanBufferTo(final long newCleanPosition)
    {
        final long cleanPosition = this.cleanPosition;
        final int bytesForCleaning = (int)(newCleanPosition - cleanPosition);
        final UnsafeBuffer dirtyTerm = termBuffers[indexByPosition(cleanPosition, positionBitsToShift)];
        final int termOffset = (int)cleanPosition & termLengthMask;
        final int length = Math.min(bytesForCleaning, dirtyTerm.capacity() - termOffset);

        if (length > 0)
        {
            dirtyTerm.setMemory(termOffset, length, (byte)0);
            this.cleanPosition = cleanPosition + length;
        }
    }
}
