package org.jurr.java.wapi.examples;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.client.WapiClient;
import org.jurr.java.wapi.client.transport.TcpWapiTransport;
import org.jurr.java.wapi.client.transport.WapiTransport;
import org.jurr.java.wapi.meter.WapiMeterData;
import org.jurr.java.wapi.meter.WapiMeterGroup;
import org.jurr.java.wapi.meter.WapiMeterRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only diagnostic example that requests meters and prints every UDP meter report with a time stamp,
 * as hex and decoded to dB, with every value labelled by its group and meter (PDF Table 5, Meter Data).
 *
 * <p>
 * Usage: {@code WapiMeterListenExample <host> <port> <groups> [seconds] [udpPort]}, where
 * {@code groups} is a comma-separated list in request order, such as {@code mon,ch28,fx1}:
 * {@code ch1}..{@code ch40}, {@code aux1}..{@code aux8}, {@code bus1}..{@code bus16},
 * {@code main1}..{@code main4} and {@code mtx1}..{@code mtx8} (8 values each, or 11 with a {@code v2}
 * suffix such as {@code ch28v2}), {@code dca1}..{@code dca8} (4 values), {@code fx1}..{@code fx16}
 * (10 values), {@code mon} (6 values) and {@code rta} (120 values). A plain number means a channel.
 * Consecutive groups of the same kind are requested with one group token followed by their indexes.
 * The default is 15 seconds and a free UDP port chosen by the operating system. A meter request only asks the console to
 * send meter data and changes no setting. The only things this example sends are the meter request,
 * and what the client sends by itself: the renewal every 4 seconds (meter data stops after 5 seconds
 * without one) and the keep-alive. The reports come in through a meter listener. When the connection
 * is lost, it reconnects every 2 seconds until the console is back, and the client then sends the
 * meter request again. The hex is the report re-encoded from its decoded values, which is identical to the
 * received bytes for a valid report.
 */
public final class WapiMeterListenExample
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	static final int REQUEST_ID = 1;
	/** Meter values of a channel, aux, bus, main or matrix, in report order (PDF Table 5, Meter Data). */
	static final String[] STRIP_LABELS = { "in L", "in R", "out L", "out R", "gate key", "gate gain", "dyn key", "dyn gain" };
	/** Meter values of the V2 variants of those groups. */
	static final String[] STRIP_V2_LABELS = { "in L", "in R", "out L", "out R", "gate key", "gate gain", "gate led", "dyn key", "dyn gain", "dyn state", "automix gain" };
	static final String[] DCA_LABELS = { "pre L", "pre R", "post L", "post R" };
	static final String[] FX_LABELS = { "in L", "in R", "out L", "out R", "state 1", "state 2", "state 3", "state 4", "state 5", "state 6" };
	static final String[] MONITOR_LABELS = { "solo L", "solo R", "mon1 L", "mon1 R", "mon2 L", "mon2 R" };
	/**
	 * Values that are not levels in 1/256 dB, printed raw: gain values use their own scale (PDF: "1.0 (256)
	 * maps to 20 dB gain reduction"), and LEDs and states are 0 or 1.
	 */
	static final Set<String> RAW_LABELS = Set.of(
			"gate gain", "dyn gain", "gate led", "dyn state", "automix gain", "state 1", "state 2", "state 3", "state 4", "state 5", "state 6");
	private static final Pattern SOURCE = Pattern.compile("([a-z]+?)(\\d*)(v2)?");
	private static final long DEFAULT_SECONDS = 15L;

	private WapiMeterListenExample()
	{
	}

	/**
	 * Runs the example; the class comment describes the arguments.
	 *
	 * @param args the command-line arguments
	 */
	public static void main(final String[] args)
	{
		if (args.length < 3 || args.length > 5)
		{
			throw new IllegalArgumentException("Usage: WapiMeterListenExample <host> <port> <groups> [seconds] [udpPort]");
		}
		final var sources = parseSources(args[2]);
		final long seconds = args.length > 3 ? Long.parseLong(args[3]) : DEFAULT_SECONDS;
		final int udpPort = args.length > 4 ? Integer.parseInt(args[4]) : 0;
		if (seconds <= 0)
		{
			throw new IllegalArgumentException("Seconds must be positive");
		}
		final var session = new WapiSession(args[0], Integer.parseInt(args[1]));
		run(session, new TcpWapiTransport(), sources, seconds * 1000L, udpPort);
	}

	/**
	 * Requests the given meters and prints the reports until the duration has passed.
	 *
	 * @param sources meter groups in the order in which they are requested
	 * @param udpPort local UDP port for the reports, or 0 for a free port chosen by the operating system
	 */
	static void run(final WapiSession session, final WapiTransport transport, final List<MeterSource> sources, final long durationMillis, final int udpPort)
	{
		try (var client = new WapiClient(session, transport))
		{
			final var retries = Executors.newSingleThreadScheduledExecutor();
			try
			{
				final long start = System.nanoTime();
				// Reconnecting is up to the application; retry on a thread of its own, not in the listener
				client.addConnectionListener(cause -> {
					LOGGER.info("{} connection lost: {}", stamp(start), cause.getMessage());
					retries.schedule(() -> reconnect(client, retries, start), 1, TimeUnit.SECONDS);
				});
				final var reports = new AtomicInteger();
				// The client renews the request by itself; the listener prints each report as it arrives
				client.addMeterListener(report -> {
					reports.incrementAndGet();
					LOGGER.info("{} report: {}", stamp(start), toHex(report));
					LOGGER.info("      {}", describe(report, sources));
				});
				final int port = client.wMeterUDPPort(udpPort);
				final var request = buildRequest(port, sources);
				LOGGER.info("{} listening on UDP port {} for {} ({} values, {} bytes per report)", stamp(start), port, names(sources), expectedValues(sources), 4 + 2 * expectedValues(sources));
				client.wSetMetersRequest(request);
				LOGGER.info("{} tx meter request: {}", stamp(start), toHex(request.encode()));

				Thread.sleep(durationMillis);
				LOGGER.info("{} reports in {} s", reports.get(), String.format(Locale.ROOT, "%.1f", durationMillis / 1000.0));
			}
			catch (final InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			finally
			{
				// Stop retrying before the client closes, so that no reconnect reopens it
				retries.shutdownNow();
			}
		}
	}

	/** Reconnects; the client then sends the meter request again. Retries every 2 seconds. */
	private static void reconnect(final WapiClient client, final ScheduledExecutorService retries, final long start)
	{
		try
		{
			client.wOpen();
			LOGGER.info("{} reconnected", stamp(start));
		}
		catch (final WapiException e)
		{
			LOGGER.info("{} reconnect failed: {}", stamp(start), e.getMessage());
			retries.schedule(() -> reconnect(client, retries, start), 2, TimeUnit.SECONDS);
		}
	}

	/**
	 * Parses a comma-separated list of meter groups, such as {@code mon,ch28,fx1}.
	 *
	 * @throws IllegalArgumentException for an unknown group, a missing or superfluous index, or an index out of range
	 */
	static List<MeterSource> parseSources(final String text)
	{
		final var sources = new ArrayList<MeterSource>();
		for (final var part : text.split(","))
		{
			sources.add(parseSource(part));
		}
		return sources;
	}

	/**
	 * Parses one meter group, such as {@code ch28}; a plain number means a channel.
	 *
	 * @throws IllegalArgumentException for an unknown group, a missing or superfluous index, or an index out of range
	 */
	private static MeterSource parseSource(final String part)
	{
		final var spec = part.trim().toLowerCase(Locale.ROOT);
		final var matcher = SOURCE.matcher(spec.matches("\\d+") ? "ch" + spec : spec);
		if (!matcher.matches())
		{
			throw new IllegalArgumentException("Unknown meter group: " + part);
		}
		final var group = group(matcher.group(1), matcher.group(3) != null, part);
		return new MeterSource(group, index(group, matcher.group(2), part), matcher.group(0), labels(group));
	}

	/**
	 * {@return the index, or 0 for a group without index}
	 *
	 * @param digits the index as written, empty when there is none
	 * @throws IllegalArgumentException for a missing or superfluous index, or an index out of range
	 */
	private static int index(final WapiMeterGroup group, final String digits, final String part)
	{
		if (digits.isEmpty() == group.hasIndex())
		{
			throw new IllegalArgumentException(group.hasIndex() ? "Meter group needs an index: " + part : "Meter group takes no index: " + part);
		}
		if (digits.isEmpty())
		{
			return 0;
		}
		final int index = Integer.parseInt(digits);
		if (index < 1 || index > group.getMaxIndex())
		{
			throw new IllegalArgumentException("Index must be between 1 and " + group.getMaxIndex() + ": " + part);
		}
		return index;
	}

	/** {@return the request, with consecutive groups of the same kind merged into one group token} */
	static WapiMeterRequest buildRequest(final int port, final List<MeterSource> sources)
	{
		final var request = new WapiMeterRequest(REQUEST_ID, port);
		var i = 0;
		while (i < sources.size())
		{
			final var group = sources.get(i).group;
			final var indexes = new ArrayList<Integer>();
			while (i < sources.size() && sources.get(i).group == group && group.hasIndex())
			{
				indexes.add(sources.get(i).index);
				i++;
			}
			if (indexes.isEmpty())
			{
				request.addGroup(group);
				i++;
			}
			else
			{
				final var array = new int[indexes.size()];
				for (var j = 0; j < array.length; j++)
				{
					array[j] = indexes.get(j);
				}
				request.addGroup(group, array);
			}
		}
		return request;
	}

	/**
	 * @return the values labelled with their group and meter: levels in dB with the raw value, gain, LED and state
	 *         values raw only, and a note when the count is unexpected
	 */
	static String describe(final WapiMeterData report, final List<MeterSource> sources)
	{
		final var raw = report.rawValues();
		final var decibels = report.decibelValues();
		final var text = new StringBuilder("id=" + report.requestId());
		var value = 0;
		for (final var source : sources)
		{
			for (final var label : source.labels)
			{
				if (value < raw.size())
				{
					append(text, source.name + " " + label, decibels.get(value), raw.get(value), RAW_LABELS.contains(label));
				}
				value++;
			}
		}
		for (int i = value; i < raw.size(); i++)
		{
			append(text, "value " + (i + 1), decibels.get(i), raw.get(i), false);
		}
		if (raw.size() != expectedValues(sources))
		{
			text.append(" [expected ").append(expectedValues(sources)).append(" values, got ").append(raw.size()).append(']');
		}
		return text.toString();
	}

	private static void append(final StringBuilder text, final String label, final float decibels, final int raw, final boolean rawOnly)
	{
		text.append(", ").append(label).append('=');
		text.append(rawOnly ? raw + " (raw)" : String.format(Locale.ROOT, "%.2f dB (%d)", decibels, raw));
	}

	private static int expectedValues(final List<MeterSource> sources)
	{
		var count = 0;
		for (final var source : sources)
		{
			count += source.labels.length;
		}
		return count;
	}

	private static String names(final List<MeterSource> sources)
	{
		final var names = new ArrayList<String>();
		for (final var source : sources)
		{
			names.add(source.name);
		}
		return String.join(", ", names);
	}

	private static WapiMeterGroup group(final String prefix, final boolean v2, final String part)
	{
		final var strip = switch (prefix)
		{
		case "ch" -> v2 ? WapiMeterGroup.CHANNEL_V2 : WapiMeterGroup.CHANNEL;
		case "aux" -> v2 ? WapiMeterGroup.AUX_V2 : WapiMeterGroup.AUX;
		case "bus" -> v2 ? WapiMeterGroup.BUS_V2 : WapiMeterGroup.BUS;
		case "main" -> v2 ? WapiMeterGroup.MAIN_V2 : WapiMeterGroup.MAIN;
		case "mtx" -> v2 ? WapiMeterGroup.MATRIX_V2 : WapiMeterGroup.MATRIX;
		default -> null;
		};
		if (strip != null)
		{
			return strip;
		}
		if (v2)
		{
			throw new IllegalArgumentException("Only ch, aux, bus, main and mtx have a v2 variant: " + part);
		}
		return switch (prefix)
		{
		case "dca" -> WapiMeterGroup.DCA;
		case "fx" -> WapiMeterGroup.FX;
		case "mon" -> WapiMeterGroup.MONITOR;
		case "rta" -> WapiMeterGroup.RTA;
		default -> throw new IllegalArgumentException("Unknown meter group: " + part);
		};
	}

	private static String[] labels(final WapiMeterGroup group)
	{
		return switch (group)
		{
		case CHANNEL, AUX, BUS, MAIN, MATRIX -> STRIP_LABELS;
		case CHANNEL_V2, AUX_V2, BUS_V2, MAIN_V2, MATRIX_V2 -> STRIP_V2_LABELS;
		case DCA -> DCA_LABELS;
		case FX -> FX_LABELS;
		case MONITOR -> MONITOR_LABELS;
		case RTA -> IntStream.rangeClosed(1, 120).mapToObj(slot -> "slot " + slot).toArray(String[]::new);
		default -> throw new IllegalArgumentException("No fixed meter layout for " + group);
		};
	}

	/** One requested meter group: the group, its one-based index (0 for a group without index), its name and its value labels. */
	record MeterSource(WapiMeterGroup group, int index, String name, String[] labels)
	{
	}

	/** {@return the report as hex: the 4-byte request ID followed by the signed 16-bit values} */
	static String toHex(final WapiMeterData report)
	{
		final var hex = new StringBuilder(String.format("%08x", report.requestId()));
		for (final int value : report.rawValues())
		{
			hex.append(String.format("%04x", value & 0xFFFF));
		}
		return hex.toString();
	}

	private static String toHex(final byte[] bytes)
	{
		return HexFormat.of().formatHex(bytes);
	}

	private static String stamp(final long start)
	{
		return String.format("[+%6d ms]", (System.nanoTime() - start) / 1_000_000L);
	}
}
