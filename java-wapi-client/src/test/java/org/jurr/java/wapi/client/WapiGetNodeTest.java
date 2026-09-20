package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiTokenValue;
import org.jurr.java.wapi.WapiValue;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiEncoder;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;

/** Expected strings are the output of C {@code wGetNode} (libwapi.a) for the same console state. */
class WapiGetNodeTest
{
	/** Captured reply to the data request for ch.1.flt (see WapiBinaryNodeTest). */
	private static final String FLT_REPLY = """
			d7ae9ed8af01d7401e6cf6d541e89b02d7d82e0a59813234d7826f399401d7a0426589d5460a7fa2
			d74b51a9958036d71645ae3b00d7104ec6788354494c54d74bc3ed08d500000000de""";
	private static final String[] FLT_PARAMETERS = {
			"ch.1.flt.lc", "ch.1.flt.lcf", "ch.1.flt.lcs", "ch.1.flt.hc", "ch.1.flt.hcf", "ch.1.flt.hcs", "ch.1.flt.tf", "ch.1.flt.mdl", "ch.1.flt.1"
	};
	/** Captured reply to the data request for ch.1.fdr: d738ae75c2 d5c0a66668 de (-5.2 dB). */
	private static final String FDR_REPLY = "d738ae75c2d5c0a66668de";

	@Test
	void formatsCapturedNodeReplyLikeC()
	{
		final var transport = new ScriptedWapiTransport();
		final var channelCodec = new WapiNativeChannelCodec();
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(FLT_REPLY, 256)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			register(client, FLT_PARAMETERS);

			assertEquals(".lc=1,lcf=29.08,lcs=24,hc=1,hcf=8863.91,hcs=6,tf=0,mdl=TILT,1=0.00,\n", client.wGetNode(new WapiToken("ch.1.flt")));

			// d7 <ch.1.flt> dc: data request
			final var hash = new WapiToken("ch.1.flt").hashBytes();
			assertArrayEquals(new byte[] {
					(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3], (byte) WapiBinaryStreamCodec.TOKEN_DATA_REQUEST
			},
					channelCodec.decode(transport.getSentPackets().get(0)).get(0).payload());
		}
	}

	@Test
	void formatsNestedPathsRelativeToThePreviousNodeLikeC()
	{
		// A subset of the captured ch.1 reply, in the console's order
		final var reply = List.of(
				value("ch.1.in.set.$mode", WapiValue.ofString("M")),
				value("ch.1.in.set.srcauto", WapiValue.ofInt(1)),
				value("ch.1.in.conn.grp", WapiValue.ofString("A")),
				value("ch.1.flt.lc", WapiValue.ofInt(1)),
				value("ch.1.clink", WapiValue.ofInt(1)),
				value("ch.1.name", WapiValue.ofString("")),
				value("ch.1.$name", WapiValue.ofString("KICK")),
				value("ch.1.fdr", WapiValue.ofFloat(-5.2F)),
				value("ch.1.peq.on", WapiValue.ofInt(0)),
				value("ch.1.main.1.on", WapiValue.ofInt(1)),
				value("ch.1.main.2.on", WapiValue.ofInt(0)),
				value("ch.1.send.1.lvl", WapiValue.ofFloat(-144F)),
				value("ch.1.send.MX8.pan", WapiValue.ofFloat(0F)),
				value("ch.1.tapwid", WapiValue.ofFloat(100F)),
				value("ch.1.postins.on", WapiValue.ofInt(0)),
				value("ch.1.tags", WapiValue.ofString("")));
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new WapiEncoder().encodeNativeNodeValues(reply)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			for (final var tokenValue : reply)
			{
				client.getTokenRegistry().register(tokenValue.token());
			}

			assertEquals(".in.set.$mode=M,srcauto=1,.conn.grp=A,..flt.lc=1,.clink=1,name=\"\",$name=KICK,fdr=-5.20,peq.on=0,"
					+ ".main.1.on=1,.2.on=0,..send.1.lvl=-144.00,.MX8.pan=0.00,..tapwid=100.00,postins.on=0,.tags=\"\",\n",
					client.wGetNode(new WapiToken("ch.1")));
		}
	}

	@Test
	void writesStringsWithSpacesWithoutQuotesLikeC()
	{
		// From the captured ch.25 reply: C writes name=IN-23/24,$name=INPRIK FRONT
		final var reply = List.of(
				value("ch.25.name", WapiValue.ofString("IN-23/24")),
				value("ch.25.$name", WapiValue.ofString("INPRIK FRONT")));
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new WapiEncoder().encodeNativeNodeValues(reply)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			register(client, "ch.25.name", "ch.25.$name");

			assertEquals(".name=IN-23/24,$name=INPRIK FRONT,\n", client.wGetNode(new WapiToken("ch.25")));
		}
	}

	@Test
	void printsUnregisteredParametersAsHashAndRestartsFromTheRoot()
	{
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(FLT_REPLY, 256)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			register(client, "ch.1.flt.lc", "ch.1.flt.lcs", "ch.1.flt.hc", "ch.1.flt.hcf", "ch.1.flt.hcs", "ch.1.flt.tf", "ch.1.flt.mdl", "ch.1.flt.1");

			// ch.1.flt.lcf (hash 401e6cf6) is not registered
			assertEquals(".lc=1,0x401E6CF6=29.08,/ch.1.flt.lcs=24,hc=1,hcf=8863.91,hcs=6,tf=0,mdl=TILT,1=0.00,\n",
					client.wGetNode(new WapiToken("ch.1.flt")));
		}
	}

	@Test
	void rejectsParameterLikeC()
	{
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(FDR_REPLY, 64)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var token = new WapiToken("ch.1.fdr");
			final var exception = assertThrows(WapiException.class, () -> client.wGetNode(token));

			assertEquals(WapiError.WNODE, exception.getError());
		}
	}

	@Test
	void keepsUnsolicitedEventsQueued() throws Exception
	{
		final var mute = new WapiToken("ch.2.mute");
		final var transport = new ScriptedWapiTransport();
		final var channelCodec = new WapiNativeChannelCodec();
		final byte[] muteHash = mute.hashBytes();
		// d7 <ch.2.mute> 01: an unsolicited event (no end of data) before the reply
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, muteHash[0], muteHash[1], muteHash[2], muteHash[3], WapiBinaryStreamCodec.TOKEN_TRUE
		}));
		transport.enqueueResponse(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(FLT_REPLY, 256)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			register(client, FLT_PARAMETERS);
			client.getTokenRegistry().register(mute);
			final var heard = new ArrayList<WapiEvent>();
			client.addEventListener(heard::add);

			assertEquals(".lc=1,lcf=29.08,lcs=24,hc=1,hcf=8863.91,hcs=6,tf=0,mdl=TILT,1=0.00,\n", client.wGetNode(new WapiToken("ch.1.flt")));

			final var events = client.wGetParsedEvents(2, Duration.ZERO);
			assertEquals(1, events.size());
			assertEquals(mute, events.get(0).token());
			client.awaitListeners();
			assertEquals(1, heard.size());
		}
	}

	private static void register(final WapiClient client, final String... names)
	{
		for (final var name : names)
		{
			client.getTokenRegistry().register(new WapiToken(name));
		}
	}

	private static WapiTokenValue value(final String name, final WapiValue value)
	{
		return new WapiTokenValue(new WapiToken(name), value);
	}
}
