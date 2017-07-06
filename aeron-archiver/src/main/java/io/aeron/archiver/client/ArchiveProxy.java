/*
 * Copyright 2014-2017 Real Logic Ltd.
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
package io.aeron.archiver.client;

import io.aeron.*;
import io.aeron.archiver.codecs.*;
import org.agrona.*;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;

/**
 * Proxy class for encapsulating interaction with an Archive control protocol.
 */
public class ArchiveProxy
{
    private static final int HEADER_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH;

    private final int maxRetryAttempts;
    private final IdleStrategy retryIdleStrategy;

    private final ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(2048);
    private final Publication controlRequest;
    private final Subscription recordingEvents;
    private final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
    private final ConnectRequestEncoder connectRequestEncoder = new ConnectRequestEncoder();
    private final StartRecordingRequestEncoder startRecordingRequestEncoder = new StartRecordingRequestEncoder();
    private final ReplayRequestEncoder replayRequestEncoder = new ReplayRequestEncoder();
    private final AbortReplayRequestEncoder abortReplayRequestEncoder = new AbortReplayRequestEncoder();
    private final StopRecordingRequestEncoder stopRecordingRequestEncoder = new StopRecordingRequestEncoder();
    private final ListRecordingsRequestEncoder listRecordingsRequestEncoder = new ListRecordingsRequestEncoder();

    private final MessageHeaderDecoder messageHeaderDecoder = new MessageHeaderDecoder();
    private final RecordingStartedDecoder recordingStartedDecoder = new RecordingStartedDecoder();
    private final RecordingProgressDecoder recordingProgressDecoder = new RecordingProgressDecoder();
    private final RecordingStoppedDecoder recordingStoppedDecoder = new RecordingStoppedDecoder();
    private final ReplayAbortedDecoder replayAbortedDecoder = new ReplayAbortedDecoder();
    private final ReplayStartedDecoder replayStartedDecoder = new ReplayStartedDecoder();
    private final RecordingDescriptorDecoder recordingDescriptorDecoder = new RecordingDescriptorDecoder();
    private final ControlResponseDecoder archiverResponseDecoder = new ControlResponseDecoder();
    private final RecordingNotFoundResponseDecoder recordingNotFoundResponseDecoder =
        new RecordingNotFoundResponseDecoder();

    /**
     * Create a proxy with a {@link Publication} for sending control message requests and a {@link Subscription} for
     * receiving events about the progress of recordings.
     *
     * This provides a default {@link IdleStrategy} of a {@link YieldingIdleStrategy} when offers are back pressured
     * with a default maximum retry attempts of 3.
     *
     * @param controlRequest  publication for sending control messages to an archive.
     * @param recordingEvents subscription for receiving event messages about the progress of recordings.
     */
    public ArchiveProxy(final Publication controlRequest, final Subscription recordingEvents)
    {
        this(controlRequest, recordingEvents, new YieldingIdleStrategy(), 3);
    }

    /**
     * Create a proxy with a {@link Publication} for sending control message requests and a {@link Subscription} for
     * receiving events about the progress of recordings.
     *
     * @param controlRequest    publication for sending control messages to an archive.
     * @param recordingEvents   subscription for receiving event messages about the progress of recordings.
     * @param retryIdleStrategy for what should happen between retry attempts at offering messages.
     * @param maxRetryAttempts  for offering control messages before giving up.
     */
    public ArchiveProxy(
        final Publication controlRequest,
        final Subscription recordingEvents,
        final IdleStrategy retryIdleStrategy,
        final int maxRetryAttempts)
    {
        this.controlRequest = controlRequest;
        this.recordingEvents = recordingEvents;
        this.retryIdleStrategy = retryIdleStrategy;
        this.maxRetryAttempts = maxRetryAttempts;
    }

    /**
     * Connect to an archive on its control interface providing the response stream details.
     *
     * @param responseChannel  for the control message responses.
     * @param responseStreamId for the control message responses.
     * @return true if successfully offered otherwise false.
     */
    public boolean connect(final String responseChannel, final int responseStreamId)
    {
        connectRequestEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .responseStreamId(responseStreamId)
            .responseChannel(responseChannel);

        return offer(connectRequestEncoder.encodedLength());
    }

    /**
     * Start recording streams for a given channel and stream id pairing.
     *
     * @param channel       to be recorded.
     * @param streamId      to be recorded.
     * @param correlationId for this request.
     * @return true if successfully offered otherwise false.
     */
    public boolean startRecording(final String channel, final int streamId, final long correlationId)
    {
        startRecordingRequestEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .correlationId(correlationId)
            .streamId(streamId)
            .channel(channel);

        return offer(startRecordingRequestEncoder.encodedLength());
    }

    /**
     * Stop an active recording.
     *
     * @param recordingId   to be stopped.
     * @param correlationId for this request.
     * @return true if successfully offered otherwise false.
     */
    public boolean stopRecording(final long recordingId, final long correlationId)
    {
        stopRecordingRequestEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .correlationId(correlationId)
            .recordingId(recordingId);

        return offer(stopRecordingRequestEncoder.encodedLength());
    }

    /**
     * Replay a recording from a given position.
     *
     * @param recordingId    to be replayed.
     * @param position       from which the replay should be started.
     * @param length         of the stream to be replayed.
     * @param replayChannel  to which the replay should be sent.
     * @param replayStreamId to which the replay should be sent.
     * @param correlationId  for this request.
     * @return true if successfully offered otherwise false.
     */
    public boolean replay(
        final long recordingId,
        final long position,
        final long length,
        final String replayChannel,
        final int replayStreamId,
        final long correlationId)
    {
        replayRequestEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .correlationId(correlationId)
            .recordingId(recordingId)
            .position(position)
            .length(length)
            .replayStreamId(replayStreamId)
            .replayChannel(replayChannel);

        return offer(replayRequestEncoder.encodedLength());
    }

    /**
     * Abort an active replay.
     *
     * @param replayId      to be aborted.
     * @param correlationId for this request.
     * @return true if successfully offered otherwise false.
     */
    public boolean abortReplay(final int replayId, final long correlationId)
    {
        abortReplayRequestEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .correlationId(correlationId)
            .replayId(replayId);

        return offer(abortReplayRequestEncoder.encodedLength());
    }

    /**
     * List a range of recording descriptors.
     *
     * @param fromRecordingId at which to begin listing.
     * @param recordCount     for the number of descriptors to be listed.
     * @param correlationId   for this request.
     * @return true if successfully offered otherwise false.
     */
    public boolean listRecordings(final long fromRecordingId, final int recordCount, final long correlationId)
    {
        listRecordingsRequestEncoder
            .wrapAndApplyHeader(buffer, 0, messageHeaderEncoder)
            .correlationId(correlationId)
            .fromRecordingId(fromRecordingId)
            .recordCount(recordCount);

        return offer(listRecordingsRequestEncoder.encodedLength());
    }

    /**
     * Poll for responses to control message requests.
     *
     * @param controlSubscription for the response messages.
     * @param responseListener    on to which responses are delegated.
     * @param fragmentLimit       to limit the batch size of a polling operation.
     * @return the number of fragments delivered.
     */
    public int pollControlResponses(
        final Subscription controlSubscription,
        final ResponseListener responseListener,
        final int fragmentLimit)
    {
        return controlSubscription.poll(
            (buffer, offset, length, header) ->
            {
                messageHeaderDecoder.wrap(buffer, offset);

                final int templateId = messageHeaderDecoder.templateId();
                switch (templateId)
                {
                    case ControlResponseDecoder.TEMPLATE_ID:
                        handleArchiverResponse(responseListener, buffer, offset);
                        break;

                    case ReplayAbortedDecoder.TEMPLATE_ID:
                        handleReplayAborted(responseListener, buffer, offset);
                        break;

                    case ReplayStartedDecoder.TEMPLATE_ID:
                        handleReplayStarted(responseListener, buffer, offset);
                        break;

                    case RecordingDescriptorDecoder.TEMPLATE_ID:
                        handleRecordingDescriptor(responseListener, buffer, offset);
                        break;

                    case RecordingNotFoundResponseDecoder.TEMPLATE_ID:
                        handleRecordingNotFoundResponse(responseListener, buffer, offset);
                        break;

                    default:
                        throw new IllegalStateException("Unknown templateId: " + templateId);
                }
            },
            fragmentLimit);
    }

    private void handleRecordingNotFoundResponse(
        final ResponseListener responseListener,
        final DirectBuffer buffer,
        final int offset)
    {
        recordingNotFoundResponseDecoder.wrap(
            buffer,
            offset + HEADER_LENGTH,
            messageHeaderDecoder.blockLength(),
            messageHeaderDecoder.version());

        responseListener.onRecordingNotFound(
            recordingNotFoundResponseDecoder.correlationId(),
            recordingNotFoundResponseDecoder.recordingId(),
            recordingNotFoundResponseDecoder.maxRecordingId());
    }

    private void handleArchiverResponse(
        final ResponseListener responseListener,
        final DirectBuffer buffer,
        final int offset)
    {
        archiverResponseDecoder.wrap(
            buffer,
            offset + HEADER_LENGTH,
            messageHeaderDecoder.blockLength(),
            messageHeaderDecoder.version());

        final long correlationId = archiverResponseDecoder.correlationId();
        final ControlResponseCode code = archiverResponseDecoder.code();
        responseListener.onResponse(correlationId, code, archiverResponseDecoder.errorMessage());
    }

    private void handleReplayAborted(
        final ResponseListener responseListener,
        final DirectBuffer buffer,
        final int offset)
    {
        replayAbortedDecoder.wrap(
            buffer,
            offset + HEADER_LENGTH,
            messageHeaderDecoder.blockLength(),
            messageHeaderDecoder.version());

        responseListener.onReplayAborted(replayAbortedDecoder.correlationId(), replayAbortedDecoder.endPosition());
    }

    private void handleReplayStarted(
        final ResponseListener responseListener,
        final DirectBuffer buffer,
        final int offset)
    {
        replayStartedDecoder.wrap(
            buffer,
            offset + HEADER_LENGTH,
            messageHeaderDecoder.blockLength(),
            messageHeaderDecoder.version());

        responseListener.onReplayStarted(replayStartedDecoder.correlationId(), replayStartedDecoder.replayId());
    }

    private void handleRecordingDescriptor(
        final ResponseListener responseListener,
        final DirectBuffer buffer,
        final int offset)
    {
        recordingDescriptorDecoder.wrap(
            buffer,
            offset + HEADER_LENGTH,
            messageHeaderDecoder.blockLength(),
            messageHeaderDecoder.version());

        responseListener.onRecordingDescriptor(
            recordingDescriptorDecoder.correlationId(),
            recordingDescriptorDecoder.recordingId(),
            recordingDescriptorDecoder.joinTimestamp(),
            recordingDescriptorDecoder.endTimestamp(),
            recordingDescriptorDecoder.joinPosition(),
            recordingDescriptorDecoder.endTimestamp(),
            recordingDescriptorDecoder.initialTermId(),
            recordingDescriptorDecoder.termBufferLength(),
            recordingDescriptorDecoder.mtuLength(),
            recordingDescriptorDecoder.segmentFileLength(),
            recordingDescriptorDecoder.sessionId(),
            recordingDescriptorDecoder.streamId(),
            recordingDescriptorDecoder.channel(),
            recordingDescriptorDecoder.sourceIdentity());
    }

    public int pollRecordingEvents(final RecordingEventsListener recordingEventsListener, final int fragmentLimit)
    {
        return recordingEvents.poll(
            (buffer, offset, length, header) ->
            {
                messageHeaderDecoder.wrap(buffer, offset);

                final int templateId = messageHeaderDecoder.templateId();
                switch (templateId)
                {
                    case RecordingStartedDecoder.TEMPLATE_ID:
                        recordingStartedDecoder.wrap(
                            buffer,
                            offset + MessageHeaderDecoder.ENCODED_LENGTH,
                            messageHeaderDecoder.blockLength(),
                            messageHeaderDecoder.version());

                        recordingEventsListener.onStart(
                            recordingStartedDecoder.recordingId(),
                            recordingStartedDecoder.joinPosition(),
                            recordingStartedDecoder.sessionId(),
                            recordingStartedDecoder.streamId(),
                            recordingStartedDecoder.channel(),
                            recordingStartedDecoder.sourceIdentity());
                        break;

                    case RecordingProgressDecoder.TEMPLATE_ID:
                        recordingProgressDecoder.wrap(
                            buffer,
                            offset + MessageHeaderDecoder.ENCODED_LENGTH,
                            messageHeaderDecoder.blockLength(),
                            messageHeaderDecoder.version());

                        recordingEventsListener.onProgress(
                            recordingProgressDecoder.recordingId(),
                            recordingProgressDecoder.joinPosition(),
                            recordingProgressDecoder.position());
                        break;

                    case RecordingStoppedDecoder.TEMPLATE_ID:
                        recordingStoppedDecoder.wrap(
                            buffer,
                            offset + MessageHeaderDecoder.ENCODED_LENGTH,
                            messageHeaderDecoder.blockLength(),
                            messageHeaderDecoder.version());

                        recordingEventsListener.onStop(
                            recordingStoppedDecoder.recordingId(),
                            recordingStoppedDecoder.joinPosition(),
                            recordingStoppedDecoder.endPosition());
                        break;

                    default:
                        throw new IllegalStateException("Unknown templateId: " + templateId);
                }
            },
            fragmentLimit);
    }

    private boolean offer(final int length)
    {
        retryIdleStrategy.reset();

        int attempts = 0;
        while (true)
        {
            if (controlRequest.offer(buffer, 0, HEADER_LENGTH + length) > 0)
            {
                return true;
            }

            if (++attempts > maxRetryAttempts)
            {
                return false;
            }

            retryIdleStrategy.idle();
        }
    }
}