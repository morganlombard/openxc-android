package com.openxc.sources;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.common.base.CharMatcher;
import com.google.common.io.LimitInputStream;
import com.google.protobuf.CodedInputStream;
import com.openxc.BinaryMessages;

/**
 * A "mixin" of sorts to be used with object composition, this contains
 * functionality common to data sources that received streams of bytes.
 */
public class BytestreamBuffer {
    private final static String TAG = "BytestreamBuffer";

    private ByteArrayOutputStream mBuffer = new ByteArrayOutputStream();
    private double mBytesReceived = 0;
    private double mLastLoggedTransferStatsAtByte = 0;
    private final long mStartTime = System.nanoTime();

    /**
     * Add additional bytes to the buffer from the data source.
     *
     * @param bytes an array of bytes received from the interface.
     * @param length number of bytes received, and thus the amount that should
     *      be read from the array.
     */
    public void receive(byte[] bytes, int length) {
        mBuffer.write(bytes, 0, length);
        mBytesReceived += length;

        logTransferStats();
    }

    /**
     * Parse the current byte buffer to find messages. Any messages found in the
     * buffer are removed and returned.
     *
     * @return A list of messages parsed and subsequently removed from the
     *      buffer, if any.
     */
    public List<String> readLines() {
        List<String> result;
        String bufferedString = mBuffer.toString();
        if(bufferedString.indexOf("\n") != -1) {
            String[] records = bufferedString.toString().split("\n", -1);

            mBuffer = new ByteArrayOutputStream();
            result = Arrays.asList(records).subList(0, records.length - 1);

            // Preserve any remaining, trailing incomplete messages in the
            // buffer
            if(records[records.length - 1].length() > 0) {
                byte[] remainingData = records[records.length - 1].getBytes();
                mBuffer.write(remainingData, 0, remainingData.length);
            }
        } else {
            result = new ArrayList<String>();
        }

        return result;
    }

    private static boolean validateProtobuf(BinaryMessages.VehicleMessage message) {
        // TODO  this seems really out of place
        return ((message.hasNumericMessage() && message.getNumericMessage().hasName() && message.getNumericMessage().hasValue())
                    || (message.hasBooleanMessage() && message.getBooleanMessage().hasName() && message.getBooleanMessage().hasValue())
                    || (message.hasStringMessage() && message.getStringMessage().hasName() && message.getStringMessage().hasValue())
                    || (message.hasEventedStringMessage() && message.getEventedStringMessage().hasName() && message.getEventedStringMessage().hasValue() && message.getEventedStringMessage().hasEvent())
                    || (message.hasEventedBooleanMessage() && message.getEventedBooleanMessage().hasName() && message.getEventedBooleanMessage().hasValue() && message.getEventedBooleanMessage().hasEvent())
                    || (message.hasEventedNumericMessage() && message.getEventedNumericMessage().hasName() && message.getEventedNumericMessage().hasValue() && message.getEventedNumericMessage().hasEvent()))
            // TODO raw messages aren't supported upstream in the library at the
            // moment so we forcefully reject it here
                || (false && message.hasRawMessage() &&
                        message.getRawMessage().hasMessageId() &&
                        message.getRawMessage().hasData());
    }

    public BinaryMessages.VehicleMessage readBinaryMessage() {
        // TODO we could move this to a separate thread and use a
        // piped input stream, where it would block on the
        // bytestream until more data was available - but that might
        // be messy rather than this approach, which is just
        // inefficient
        InputStream input = new ByteArrayInputStream(
                mBuffer.toByteArray());
        BinaryMessages.VehicleMessage message = null;
        while(message == null) {
            try {
                int firstByte = input.read();
                if (firstByte == -1) {
                    return null;
                }

                int size = CodedInputStream.readRawVarint32(firstByte, input);
                message = BinaryMessages.VehicleMessage.parseFrom(
                        new LimitInputStream(input, size));

                if(message != null && !validateProtobuf(message)) {
                    message = null;
                } else {
                    mBuffer = new ByteArrayOutputStream();
                    int remainingByte;
                    while((remainingByte = input.read()) != -1) {
                        mBuffer.write(remainingByte);
                    }
                }
            } catch(IOException e) { }

        }
        return message;
    }

    /**
     * Return true if the buffer *most likely* contains JSON (as opposed to a
     * protobuf).
     */
    public boolean containsJson() {
        return CharMatcher.ASCII.and(
                CharMatcher.JAVA_ISO_CONTROL.and(CharMatcher.WHITESPACE.negate()).negate()
                    ).matchesAllOf(mBuffer.toString());
    }

    private void logTransferStats() {
        // log the transfer stats roughly every 1MB
        if(mBytesReceived > mLastLoggedTransferStatsAtByte + 1024 * 1024) {
            mLastLoggedTransferStatsAtByte = mBytesReceived;
            SourceLogger.logTransferStats(TAG, mStartTime, System.nanoTime(),
                    mBytesReceived);
        }
    }

}
