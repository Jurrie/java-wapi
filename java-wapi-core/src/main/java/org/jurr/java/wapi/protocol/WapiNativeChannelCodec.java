package org.jurr.java.wapi.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Encodes and decodes WING native channel framing and escape sequences. */
public class WapiNativeChannelCodec
{
	/** Escape byte: followed by a channel selector, or by {@code 0xde} for a literal {@code 0xdf} (PDF "Communication Channels"). */
	public static final int NRP_ESCAPE_CODE = 0xDF;
	/** Base of the channel selector: {@code 0xdf, 0xd<ChID>} selects channel ID {@code ChID} (PDF "Communication Channels"). */
	public static final int NRP_CHANNEL_ID_BASE = 0xD0;
	/** Number of communication channels (PDF Table 1, Channel Usage). */
	public static final int NRP_NUM_CHANNELS = 14;
	/** Channel ID 1: audio engine and control requests (PDF Table 1, Channel Usage). */
	public static final int NRP_AUDIO_ENGINE_CHANNEL_ID = 1;
	/** Channel ID 3: meter data requests (PDF Table 1, Channel Usage). */
	public static final int NRP_METER_CHANNEL_ID = 3;

	/** Creates a codec whose decoder has no channel selected yet. */
	public WapiNativeChannelCodec()
	{
	}

	/**
	 * Frames payload data for one of the 14 native channels.
	 *
	 * @param channelId wire channel ID from 0 through {@code NRP_NUM_CHANNELS - 1}
	 * @param data non-null payload bytes
	 * @return the encoded packet, beginning with escape and channel markers
	 * @throws IllegalArgumentException if the channel number is outside the supported range
	 */
	public WapiPacket encode(final int channelId, final byte[] data)
	{
		return new WapiPacket(encodeToByteArray(channelId, data));
	}

	/**
	 * Frames the payload of a channel frame for its channel.
	 *
	 * @param packet non-null frame
	 * @return the encoded packet, beginning with escape and channel markers
	 * @throws IllegalArgumentException if the channel number is outside the supported range
	 */
	public WapiPacket encode(final ChannelFrame packet)
	{
		return encode(packet.channelId, packet.payload);
	}

	/**
	 * Frames payload data for one of the 14 native channels.
	 *
	 * @param channelId wire channel ID from 0 through {@code NRP_NUM_CHANNELS - 1}
	 * @param data non-null payload bytes
	 * @return framed and escaped bytes, beginning with escape and channel markers
	 * @throws IllegalArgumentException if the channel number is outside the supported range
	 */
	private byte[] encodeToByteArray(final int channelId, final byte[] data)
	{
		if (channelId < 0 || channelId >= NRP_NUM_CHANNELS)
		{
			throw new IllegalArgumentException("Channel id must be between 0 and " + (NRP_NUM_CHANNELS - 1));
		}

		final var encoded = new ByteArrayOutputStream(data.length + 4);
		encoded.write(NRP_ESCAPE_CODE);
		encoded.write(NRP_CHANNEL_ID_BASE + channelId);

		var escaped = false;
		for (final byte db : data)
		{
			final var value = db & 0xFF;
			if (escaped && needsEscapeAfterEscapeCode(value))
			{
				encoded.write(NRP_ESCAPE_CODE - 1);
			}
			encoded.write(value);
			escaped = value == NRP_ESCAPE_CODE;
		}

		if (escaped)
		{
			encoded.write(NRP_ESCAPE_CODE - 1);
		}
		return encoded.toByteArray();
	}

	/**
	 * Decodes received bytes into the frames of their channels. The active channel, and an escape byte
	 * at the end of the packet, carry over to the next packet.
	 *
	 * @param packet received bytes, or {@code null}
	 * @return the frames in the order they arrived; empty for {@code null} or an empty packet
	 */
	public List<ChannelFrame> decode(final WapiPacket packet)
	{
		if (packet == null)
		{
			return Collections.emptyList();
		}
		return decodeFromByteArray(packet.payload());
	}

	// -- Stuff below is for decoding only - should we split this class in an encoder and decoder?

	private int activeChannelId = -1;
	private boolean escapePending;

	/** Forgets the active receive channel and any pending escape, for example when a new connection starts. */
	public void reset()
	{
		activeChannelId = -1;
		escapePending = false;
	}

	/**
	 * Decodes all available channel data while retaining only a trailing incomplete escape.
	 *
	 * @param bytes received TCP bytes; null and empty input produce no channel data
	 * @return decoded channel data chunks in stream order
	 */
	public List<ChannelFrame> decodeFromByteArray(final byte[] bytes)
	{
		if (bytes == null || bytes.length == 0)
		{
			return List.of();
		}

		final var result = new ArrayList<ChannelFrame>();
		final var payload = new ByteArrayOutputStream();
		for (byte rawByte : bytes)
		{
			decodeByte(rawByte & 0xFF, result, payload);
		}

		flush(result, payload);
		return result;
	}

	/** Decodes one received byte: an escape, the byte after an escape, or payload of the active channel. */
	private void decodeByte(final int value, final List<ChannelFrame> result, final ByteArrayOutputStream payload)
	{
		if (escapePending)
		{
			escapePending = false;
			if (decodeEscaped(value, result, payload))
			{
				return;
			}
		}
		if (value == WapiNativeChannelCodec.NRP_ESCAPE_CODE)
		{
			escapePending = true;
		}
		else
		{
			writePayload(payload, value);
		}
	}

	/**
	 * Decodes the byte after an escape byte: {@code NRP_ESCAPE_CODE - 1} stands for a literal escape
	 * byte, a channel marker selects a channel, and any other byte means the escape byte was literal.
	 *
	 * @return whether the byte is consumed; otherwise it is decoded as usual
	 */
	private boolean decodeEscaped(final int value, final List<ChannelFrame> result, final ByteArrayOutputStream payload)
	{
		if (value == WapiNativeChannelCodec.NRP_ESCAPE_CODE - 1)
		{
			writePayload(payload, WapiNativeChannelCodec.NRP_ESCAPE_CODE);
			return true;
		}
		if (isChannelMarker(value))
		{
			flush(result, payload);
			activeChannelId = value - WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE;
			return true;
		}
		writePayload(payload, WapiNativeChannelCodec.NRP_ESCAPE_CODE);
		return false;
	}

	/** Adds a byte to the payload, unless no channel is selected yet. */
	private void writePayload(final ByteArrayOutputStream payload, final int value)
	{
		if (activeChannelId >= 0)
		{
			payload.write(value);
		}
	}

	private void flush(final List<ChannelFrame> result, final ByteArrayOutputStream payload)
	{
		if (activeChannelId >= 0 && payload.size() > 0)
		{
			result.add(new ChannelFrame(activeChannelId, payload.toByteArray()));
			payload.reset();
		}
	}

	private static boolean isChannelMarker(final int value)
	{
		return value >= WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE && value < WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE + WapiNativeChannelCodec.NRP_NUM_CHANNELS;
	}

	/**
	 * Tells whether a data byte following a literal escape byte must be preceded by
	 * {@code NRP_ESCAPE_CODE - 1} on transmit. This is the channel-marker range plus
	 * {@code 0xDE} itself ({@code 0xD0..0xDE}), as in the PDF sample transmit routine;
	 * it is deliberately wider than {@link #isChannelMarker(int)}.
	 */
	private static boolean needsEscapeAfterEscapeCode(final int value)
	{
		return value >= NRP_CHANNEL_ID_BASE && value <= NRP_CHANNEL_ID_BASE + NRP_NUM_CHANNELS;
	}

	/**
	 * The data of one channel, between two channel selectors. Two frames are equal when their channel
	 * and bytes are equal.
	 *
	 * @param channelId channel, such as {@link #NRP_AUDIO_ENGINE_CHANNEL_ID}
	 * @param payload the unescaped bytes; copied in and out
	 */
	public record ChannelFrame(int channelId, byte[] payload)
	{
		/**
		 * Copies the payload in.
		 *
		 * @param channelId channel, such as {@link #NRP_AUDIO_ENGINE_CHANNEL_ID}
		 * @param payload the unescaped bytes; copied in and out
		 * @throws NullPointerException if {@code payload} is {@code null}
		 */
		public ChannelFrame
		{
			payload = payload.clone();
		}

		/** {@return a copy of the unescaped bytes} */
		@Override
		public byte[] payload()
		{
			return payload.clone();
		}

		@Override
		public boolean equals(final Object other)
		{
			return other instanceof ChannelFrame frame && channelId == frame.channelId && Arrays.equals(payload, frame.payload);
		}

		@Override
		public int hashCode()
		{
			return 31 * channelId + Arrays.hashCode(payload);
		}

		@Override
		public String toString()
		{
			return "ChannelFrame[" + channelId + ", " + WapiBinaryDataCodec.encodeHex(payload) + "]";
		}
	}
}
