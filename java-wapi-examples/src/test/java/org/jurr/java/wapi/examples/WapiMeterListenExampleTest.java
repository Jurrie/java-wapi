package org.jurr.java.wapi.examples;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import com.github.valfirst.slf4jtest.TestLoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.meter.WapiMeterData;
import org.jurr.java.wapi.protocol.ByteUtil;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;

class WapiMeterListenExampleTest
{
	@BeforeEach
	void clearLoggedEvents()
	{
		// All threads: the examples also log from their listener threads
		TestLoggerFactory.clearAll();
	}

	// 00000001 ec00 ec00 8000 8000 0000 0000 0000 0000: request 1, inputs at -20 dB, outputs at the minimum
	private static final String REPORT = "00000001ec00ec00800080000000000000000000";

	@Test
	void sendsOnlyTheMeterRequestAndKeepAliveAndPrintsReports() throws Exception
	{
		final var run = runAgainstPlayedConsole("ch28", REPORT);

		// d3 <port> d4 00000001 dc a0 1b de: channel 28 is index 1b
		assertEquals(1, run.count("d3" + run.port + "d400000001dca01bde"));
		assertTrue(run.output.contains("report: " + REPORT), run.output);
		assertTrue(run.output.contains("id=1, ch28 in L=-20.00 dB (-5120), ch28 in R=-20.00 dB (-5120), ch28 out L=-128.00 dB (-32768)"), run.output);
		assertTrue(run.output.contains("2 reports in 1.0 s"), run.output);
	}

	@Test
	void reconnectsAfterALostConnectionAndRequestsTheMetersAgain()
	{
		final var transport = new ScriptedWapiTransport();
		final var listener = new Thread(() -> WapiMeterListenExample.run(new WapiSession(), transport, WapiMeterListenExample.parseSources("ch28"), 2500L, 0));
		listener.start();
		final int port = waitForRequestedPort(transport);

		// The console closes the connection
		transport.enqueueEndOfStream();
		await("the end of the run").atMost(Duration.ofSeconds(5)).until(() -> !listener.isAlive());

		// d3 <port> d4 00000001 dc a0 1b de: once at the start and once after the reconnect
		final var request = new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_METER_CHANNEL_ID,
				WapiBinaryDataCodec.decodeHex("d3" + String.format("%04x", port) + "d400000001dca01bde", 64)).payload();
		assertEquals(2, transport.getSentPackets().stream().filter(packet -> Arrays.equals(request, packet.payload())).count());
		final var text = LoggedText.of(WapiMeterListenExample.class);
		assertTrue(text.contains(" connection lost: "), text);
		assertTrue(text.contains(" reconnected"), text);
	}

	@Test
	void requestsSeveralChannelsWithOneGroupTokenAndLabelsValuesInRequestOrder() throws Exception
	{
		// Expected: 36 bytes, the 8 values of channel 27 followed by the 8 values of channel 28
		// 00000001 | f000 f000 8000 8000 0000 0000 0000 0000 | ec00 ec00 8000 8000 0000 0000 0000 0000
		final var report = "00000001" + "f000f000800080000000000000000000" + "ec00ec00800080000000000000000000";

		final var run = runAgainstPlayedConsole("27,28", report);

		// d3 <port> d4 00000001 dc a0 1a 1b de: one channel token, then indexes 1a (27) and 1b (28)
		assertEquals(1, run.count("d3" + run.port + "d400000001dca01a1bde"));
		assertTrue(run.output.contains("id=1, ch27 in L=-16.00 dB (-4096), ch27 in R=-16.00 dB (-4096), ch27 out L=-128.00 dB (-32768)"), run.output);
		assertTrue(run.output.contains("ch27 dyn gain=0 (raw), ch28 in L=-20.00 dB (-5120), ch28 in R=-20.00 dB (-5120)"), run.output);
		assertTrue(run.output.contains("ch28 dyn gain=0 (raw)" + System.lineSeparator()), "The report must end with channel 28's last value: " + run.output);
	}

	@Test
	void labelsMixedGroupsInRequestOrder() throws Exception
	{
		// Expected: 6 monitor values, 8 channel values, 10 fx values = 24 values, 52 bytes
		// 00000001 | mon: 8000 8000 8000 8000 f400 f400 | ch28: ec00 ec00 8000 8000 0000 0000 0000 0000
		// | fx1: e000 e000 e000 e000 0001 0002 0003 0004 0005 0006
		final var report = "00000001"
				+ "8000800080008000f400f400"
				+ "ec00ec00800080000000000000000000"
				+ "e000e000e000e000000100020003000400050006";

		final var run = runAgainstPlayedConsole("mon,ch28,fx1", report);

		// d3 <port> d4 00000001 dc a9 a0 1b a6 00 de: monitor without index, channel 28, fx 1
		assertEquals(1, run.count("d3" + run.port + "d400000001dca9a01ba600de"));
		assertTrue(run.output.contains("for mon, ch28, fx1 (24 values, 52 bytes per report)"), run.output);
		assertTrue(run.output.contains("id=1, mon solo L=-128.00 dB (-32768)"), run.output);
		assertTrue(run.output.contains("mon mon2 R=-12.00 dB (-3072), ch28 in L=-20.00 dB (-5120)"), run.output);
		assertTrue(run.output.contains("ch28 dyn gain=0 (raw), fx1 in L=-32.00 dB (-8192)"), run.output);
		assertTrue(run.output.contains("fx1 state 6=6 (raw)" + System.lineSeparator()), run.output);
	}

	@Test
	void printsGainLedAndStateValuesRawInsteadOfDecibels() throws Exception
	{
		// Captured from a console: mon, ch28 (oscillator), ch1 (gate closed), fx1, dca1
		final var report = "00000001800080008000800080008000f16af16a80008000f3820000d4b20000c464c4648b8b8b8b800001008fcb0000"
				+ "b342b342acddaa4c0000000000000000000000008000800080008000";

		final var run = runAgainstPlayedConsole("mon,ch28,ch1,fx1,dca1", report);

		assertTrue(run.output.contains("ch28 in L=-14.59 dB (-3734)"), run.output);
		// PDF: gain values are not in dB; 256 (1.0) is full gain reduction
		assertTrue(run.output.contains("ch1 gate gain=256 (raw)"), run.output);
		assertTrue(run.output.contains("ch1 dyn gain=0 (raw)"), run.output);
		assertTrue(run.output.contains("fx1 state 6=0 (raw)"), run.output);
		assertTrue(run.output.contains("dca1 post R=-128.00 dB (-32768)"), run.output);
	}

	@Test
	void flagsReportWithUnexpectedLength() throws Exception
	{
		// 00000001 ec00 ec00 8000 8000 0000 0000 0000 0000: only 8 values where 24 are expected
		final var run = runAgainstPlayedConsole("mon,ch28,fx1", REPORT);

		assertTrue(run.output.contains("[expected 24 values, got 8]"), run.output);
	}

	@Test
	void mergesConsecutiveGroupsOfTheSameKind()
	{
		final var request = WapiMeterListenExample.buildRequest(0x3737, WapiMeterListenExample.parseSources("ch27,ch28,mon,ch1"));

		// d33737 d400000001 dc a01a1b a9 a000 de
		assertEquals("d33737d400000001dca01a1ba9a000de", hex(request.encode()));
	}

	@Test
	void rejectsInvalidGroups()
	{
		for (final var invalid : new String[] { "ch41", "ch", "mon3", "rtav2", "dca1v2", "xyz1", "fx0" })
		{
			assertThrows(IllegalArgumentException.class, () -> WapiMeterListenExample.parseSources(invalid), invalid);
		}
	}

	@Test
	void hexIsTheReportAsReceived()
	{
		final byte[] report = WapiBinaryDataCodec.decodeHex(REPORT, 64);

		assertEquals(REPORT, WapiMeterListenExample.toHex(WapiMeterData.decode(report)));
	}

	/** Runs the tool for one second against a scripted transport, and plays the console by sending the report twice over UDP. */
	private static Run runAgainstPlayedConsole(final String groups, final String reportHex) throws Exception
	{
		final var transport = new ScriptedWapiTransport();
		final var listener = new Thread(() -> WapiMeterListenExample.run(new WapiSession(), transport, WapiMeterListenExample.parseSources(groups), 1000L, 0));
		listener.start();

		final int port = waitForRequestedPort(transport);
		try (var console = new DatagramSocket())
		{
			final byte[] report = WapiBinaryDataCodec.decodeHex(reportHex, 256);
			console.send(new DatagramPacket(report, report.length, InetAddress.getLoopbackAddress(), port));
			console.send(new DatagramPacket(report, report.length, InetAddress.getLoopbackAddress(), port));
		}
		await("the end of the run").atMost(Duration.ofSeconds(5)).until(() -> !listener.isAlive());

		final var meterPayloads = new ArrayList<String>();
		for (final var packet : transport.getSentPackets())
		{
			final var frame = new WapiNativeChannelCodec().decode(packet).get(0);
			final var payload = hex(frame.payload());
			if (frame.channelId() == WapiNativeChannelCodec.NRP_METER_CHANNEL_ID)
			{
				meterPayloads.add(payload);
			}
			else if (!(frame.channelId() == WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID && payload.equals("d7c9cb20e0dc")))
			{
				fail("Unexpected packet on channel " + frame.channelId() + ": " + payload);
			}
		}
		for (final var payload : meterPayloads)
		{
			assertTrue(payload.startsWith("d3") || payload.equals("d400000001"), "Unexpected meter packet: " + payload);
		}
		return new Run(String.format("%04x", port), meterPayloads, LoggedText.of(WapiMeterListenExample.class));
	}

	private static int waitForRequestedPort(final ScriptedWapiTransport transport)
	{
		final var sent = await("a meter request").atMost(Duration.ofSeconds(5)).until(transport::getSentPackets, packets -> !packets.isEmpty());
		final var request = new WapiNativeChannelCodec().decode(sent.get(0)).get(0).payload();
		assertEquals(0xD3, request[0] & 0xFF, "A meter request starts with the port token");
		return ByteUtil.readUnsignedShort(request, 1);
	}

	/**
	 * What the tool sent on the meter channel and printed during one run.
	 * One run of the tool: the UDP port as hex, the payloads sent on the meter channel, and the output.
	 */
	private record Run(String port, List<String> meterPayloads, String output)
	{
		/** @return how often the given hex payload was sent on the meter channel */
		private int count(final String payload)
		{
			return (int) meterPayloads.stream().filter(payload::equals).count();
		}
	}

	private static String hex(final byte[] bytes)
	{
		return HexFormat.of().formatHex(bytes);
	}
}
