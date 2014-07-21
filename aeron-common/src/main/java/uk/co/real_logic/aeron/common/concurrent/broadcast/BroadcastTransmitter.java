/*
 * Copyright 2013 Real Logic Ltd.
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
package uk.co.real_logic.aeron.common.concurrent.broadcast;

import uk.co.real_logic.aeron.common.BitUtil;
import uk.co.real_logic.aeron.common.concurrent.AtomicBuffer;

import static uk.co.real_logic.aeron.common.concurrent.broadcast.BroadcastBufferDescriptor.*;
import static uk.co.real_logic.aeron.common.concurrent.broadcast.RecordDescriptor.*;

/**
 * Transmit messages via an underlying broadcast buffer to zero or more {@link BroadcastReceiver}s.
 *
 * <b>Note:</b> This class is not threadsafe. Only one transmitter is allow per broadcast buffer.
 */
public class BroadcastTransmitter
{
    private final AtomicBuffer buffer;
    private final int capacity;
    private final int mask;
    private final int maxMsgLength;
    private final int tailCounterIndex;
    private final int latestCounterIndex;

    /**
     * Construct a new broadcast transmitter based on an underlying {@link AtomicBuffer}.
     * The underlying buffer must a power of 2 in size plus sufficient space
     * for the {@link BroadcastBufferDescriptor#TRAILER_LENGTH}.
     *
     * @param buffer via which messages will be exchanged.
     * @throws IllegalStateException if the buffer capacity is not a power of 2
     *                               plus {@link BroadcastBufferDescriptor#TRAILER_LENGTH} in capacity.
     */
    public BroadcastTransmitter(final AtomicBuffer buffer)
    {
        this.buffer = buffer;
        this.capacity = buffer.capacity() - TRAILER_LENGTH;

        checkCapacity(capacity);

        this.mask = capacity - 1;
        this.maxMsgLength = calculateMaxMessageLength(capacity);
        this.tailCounterIndex = capacity + TAIL_COUNTER_OFFSET;
        this.latestCounterIndex = capacity + LATEST_COUNTER_OFFSET;
    }

    /**
     * Get the capacity of the underlying broadcast buffer.
     *
     * @return the capacity of the underlying broadcast buffer.
     */
    public int capacity()
    {
        return capacity;
    }

    /**
     * Get the maximum message length that can be transmitted for a buffer.
     *
     * @return the maximum message length that can be transmitted for a buffer.
     */
    public int maxMsgLength()
    {
        return maxMsgLength;
    }

    /**
     * Transmit a message to {@link BroadcastReceiver}s via the broadcast buffer.
     *
     * @param msgTypeId type of the message to be transmitted.
     * @param srcBuffer containing the encoded message to be transmitted.
     * @param srcIndex srcIndex in the source buffer at which the encoded message begins.
     * @param length in bytes of the encoded message.
     * @throws IllegalArgumentException of the msgTypeId is not valid,
     *                                  or if the message length is greater than {@link #maxMsgLength()}.
     */
    public void transmit(final int msgTypeId, final AtomicBuffer srcBuffer, final int srcIndex, final int length)
    {
        checkMsgTypeId(msgTypeId);
        checkMessageLength(length);

        long tail = buffer.getLong(tailCounterIndex);
        int recordOffset = (int)tail & mask;
        final int recordLength = BitUtil.align(length + HEADER_LENGTH, RECORD_ALIGNMENT);

        final int remainingBuffer = capacity - recordOffset;
        if (remainingBuffer < recordLength)
        {
            insertPaddingRecord(tail, recordOffset, remainingBuffer);

            tail += remainingBuffer;
            recordOffset = 0;
        }

        buffer.putLongOrdered(tailSequenceOffset(recordOffset), tail);
        buffer.putInt(recLengthOffset(recordOffset), recordLength);
        buffer.putInt(msgLengthOffset(recordOffset), length);
        buffer.putInt(msgTypeOffset(recordOffset), msgTypeId);

        buffer.putBytes(msgOffset(recordOffset), srcBuffer, srcIndex, length);

        putLatestCounter(tail);
        incrementTailOrdered(tail, recordLength);
    }

    private void putLatestCounter(final long tail)
    {
        buffer.putLong(latestCounterIndex, tail);
    }

    private void incrementTailOrdered(final long tail, final int recordLength)
    {
        buffer.putLongOrdered(tailCounterIndex, tail + recordLength);
    }

    private void insertPaddingRecord(final long tail, final int recordOffset, final int remainingBuffer)
    {
        buffer.putLongOrdered(tailSequenceOffset(recordOffset), tail);
        buffer.putInt(recLengthOffset(recordOffset), remainingBuffer);
        buffer.putInt(msgLengthOffset(recordOffset), 0);
        buffer.putInt(msgTypeOffset(recordOffset), PADDING_MSG_TYPE_ID);
    }

    private void checkMessageLength(final int length)
    {
        if (length > maxMsgLength)
        {
            final String msg = String.format("encoded message exceeds maxMsgLength of %d, length=%d",
                                             maxMsgLength, length);

            throw new IllegalArgumentException(msg);
        }
    }
}