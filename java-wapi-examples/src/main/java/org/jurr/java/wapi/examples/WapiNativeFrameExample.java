package org.jurr.java.wapi.examples;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;

import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small protocol-focused example showing native frame encoding and decoding
 * without requiring a live WING device.
 */
public final class WapiNativeFrameExample
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private WapiNativeFrameExample()
	{
	}

	/**
	 * Runs the example.
	 *
	 * @param args not used
	 */
	public static void main(final String[] args)
	{
		final var codec = new WapiNativeChannelCodec();
		final byte[] request = WapiBinaryStreamCodec.encodeNodeDefinitionRequest();
		final var frame = codec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, request);

		LOGGER.info("request bytes = {}", Arrays.toString(request));
		LOGGER.info("encoded frame = {}", Arrays.toString(frame.payload()));
		LOGGER.info("decoded payload = {}", Arrays.toString(codec.decode(frame).get(0).payload()));
	}
}
