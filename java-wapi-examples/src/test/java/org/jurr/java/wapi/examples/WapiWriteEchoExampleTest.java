package org.jurr.java.wapi.examples;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.github.valfirst.slf4jtest.TestLoggerFactory;

import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WapiWriteEchoExampleTest
{
	@BeforeEach
	void clearLoggedEvents()
	{
		// All threads: the examples also log from their listener threads
		TestLoggerFactory.clearAll();
	}

	private static final WapiToken LED = new WapiToken("$ctl.user.U1.2.led");
	private static final WapiToken NAME = new WapiToken("$ctl.user.U1.2.bu.name");

	@Test
	void readsWritesListensAndRestores()
	{
		final byte[] hash = LED.hashBytes();
		final var transport = new ScriptedWapiTransport();
		// d7 <led> 00 de: current value 0
		transport.enqueueResponse(frame(createRecord(hash, WapiBinaryStreamCodec.TOKEN_FALSE), new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_END_DATA }));
		// d7 <led> 01: echo of the write
		transport.enqueueResponse(frame(createRecord(hash, WapiBinaryStreamCodec.TOKEN_TRUE)));
		transport.enqueueTimeout();
		// d7 <led> 01 de: reply to the check after the write
		transport.enqueueResponse(frame(createRecord(hash, WapiBinaryStreamCodec.TOKEN_TRUE), new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_END_DATA }));
		// d7 <led> 00: echo of the restore
		transport.enqueueResponse(frame(createRecord(hash, WapiBinaryStreamCodec.TOKEN_FALSE)));
		transport.enqueueTimeout();
		// d7 <led> 00 de: reply to the check after the restore
		transport.enqueueResponse(frame(createRecord(hash, WapiBinaryStreamCodec.TOKEN_FALSE), new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_END_DATA }));

		WapiWriteEchoExample.run(new WapiSession(), transport, LED, "1", 100L);

		final var h = hex(hash);
		// d7 <led> dc
		final byte[] read = concat(new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH }, hash, new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_DATA_REQUEST });
		assertEquals(sent(
				read,
				// d7 <led> d400000001
				concat(new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH }, hash, new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 1 }),
				read,
				// d7 <led> d400000000
				concat(new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH }, hash, new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 0 }),
				read),
				sentPayloads(transport));
		assertArrayEquals(new String[] {
				"tx read: d7" + h + "dc",
				"rx channel 1: d7" + h + "00de",
				"      $ctl.user.U1.2.led = 0 [I32]",
				"current value: 0 [I32]",
				"tx write: d7" + h + "d400000001",
				"rx channel 1: d7" + h + "01",
				"      $ctl.user.U1.2.led = 1 [I32]",
				"tx check: d7" + h + "dc",
				"rx channel 1: d7" + h + "01de",
				"      $ctl.user.U1.2.led = 1 [I32]",
				"value after write: 1 [I32]",
				"tx restore: d7" + h + "d400000000",
				"rx channel 1: d7" + h + "00",
				"      $ctl.user.U1.2.led = 0 [I32]",
				"tx check: d7" + h + "dc",
				"rx channel 1: d7" + h + "00de",
				"      $ctl.user.U1.2.led = 0 [I32]",
				"value after restore: 0 [I32]"
		}, lines());
	}

	@Test
	void writesWithTheTypeOfTheCurrentValue()
	{
		final byte[] hash = NAME.hashBytes();
		final var transport = new ScriptedWapiTransport();
		// d7 <name> 8a 5445535420312050757368 de: current value "TEST 1 Push"
		final byte[] current = "TEST 1 Push".getBytes();
		transport.enqueueResponse(frame(createRecord(hash, 0x80 + current.length - 1), current, new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_END_DATA }));
		transport.enqueueEndOfStream();

		WapiWriteEchoExample.run(new WapiSession(), transport, NAME, "TEST 1 Echo", 100L);

		final var payloads = sentPayloads(transport);
		// The stream ends while listening, so there are no checks
		assertEquals(3, payloads.size());
		// d7 <name> d10a 544553542031204563686f: "TEST 1 Echo" as a string
		assertEquals("d7" + hex(hash) + "d10a" + hex("TEST 1 Echo".getBytes()), payloads.get(1));
		// d7 <name> d10a 5445535420312050757368: "TEST 1 Push" restored
		assertEquals("d7" + hex(hash) + "d10a" + hex(current), payloads.get(2));
	}

	@Test
	void restoresEvenWhenTheStreamEndsWhileListening()
	{
		final byte[] hash = LED.hashBytes();
		final var transport = new ScriptedWapiTransport();
		// d7 <led> 00 de, then the end of the stream
		transport.enqueueResponse(frame(createRecord(hash, WapiBinaryStreamCodec.TOKEN_FALSE), new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_END_DATA }));
		transport.enqueueEndOfStream();

		WapiWriteEchoExample.run(new WapiSession(), transport, LED, "1", 100L);

		final var payloads = sentPayloads(transport);
		assertEquals(3, payloads.size());
		// d7 <led> d400000000
		assertEquals("d7" + hex(hash) + "d400000000", payloads.get(2));
	}

	@Test
	void writesNothingWithoutCurrentValue()
	{
		// No reply: the stream ends
		final var transport = new ScriptedWapiTransport();
		transport.enqueueEndOfStream();

		WapiWriteEchoExample.run(new WapiSession(), transport, LED, "1", 100L);

		assertEquals(1, transport.getSentPackets().size(), "Only the read may be sent");
		final var lines = lines();
		assertEquals("no current value received, nothing written", lines[lines.length - 1]);
	}

	private static WapiPacket frame(final byte[]... parts)
	{
		return new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, concat(parts));
	}

	private static byte[] createRecord(final byte[] hash, final int value)
	{
		return new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3], (byte) value };
	}

	private static byte[] concat(final byte[]... parts)
	{
		final var bytes = new ByteArrayOutputStream();
		for (final var part : parts)
		{
			bytes.write(part, 0, part.length);
		}
		return bytes.toByteArray();
	}

	private static List<String> sent(final byte[]... payloads)
	{
		final var result = new ArrayList<String>();
		for (final var payload : payloads)
		{
			result.add(hex(payload));
		}
		return result;
	}

	private static List<String> sentPayloads(final ScriptedWapiTransport transport)
	{
		final var result = new ArrayList<String>();
		for (final var packet : transport.getSentPackets())
		{
			for (final var frame : new WapiNativeChannelCodec().decode(packet))
			{
				assertEquals(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, frame.channelId());
				result.add(hex(frame.payload()));
			}
		}
		return result;
	}

	private static String hex(final byte[] bytes)
	{
		return HexFormat.of().formatHex(bytes);
	}

	/** @return the output lines without their time stamps */
	private static String[] lines()
	{
		return LoggedText.of(WapiWriteEchoExample.class).trim().replaceAll("\\[\\+ *\\d+ ms\\] ", "").split("\\R");
	}
}
