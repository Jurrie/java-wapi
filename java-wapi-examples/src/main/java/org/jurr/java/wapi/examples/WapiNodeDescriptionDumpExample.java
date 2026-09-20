package org.jurr.java.wapi.examples;

import java.lang.invoke.MethodHandles;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.WapiClient;
import org.jurr.java.wapi.client.transport.TcpWapiTransport;
import org.jurr.java.wapi.node.WapiNodeDefinition;
import org.jurr.java.wapi.node.WapiNodeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only example that recursively prints WING node descriptions, with their raw flags and bytes,
 * using {@link WapiClient#getNodeDefinitions(WapiToken)}.
 *
 * <p>Usage: {@code WapiNodeDescriptionDumpExample <host> <port> [startPath] [maxDepth]}. The start
 * path is a dotted node name such as {@code ch.1}; without it, the dump starts at the top-level nodes.
 * A maximum depth of 0 prints only the direct children of the start node.
 */
public final class WapiNodeDescriptionDumpExample
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private static final int MAX_DEPTH = 128;
	private static final int MAX_DEFINITIONS = 100000;

	private WapiNodeDescriptionDumpExample()
	{
	}

	/**
	 * Runs the example; the class comment describes the arguments.
	 *
	 * @param args the command-line arguments
	 */
	public static void main(final String[] args)
	{
		if (args.length < 2 || args.length > 4)
		{
			throw new IllegalArgumentException("Usage: WapiNodeDescriptionDumpExample <host> <port> [startPath] [maxDepth]");
		}
		final var startPath = args.length > 2 ? args[2] : "";
		final int maxDepth = args.length > 3 ? Integer.parseInt(args[3]) : MAX_DEPTH;

		try (var client = new WapiClient(new WapiSession(args[0], Integer.parseInt(args[1])), new TcpWapiTransport()))
		{
			dump(client, startPath, maxDepth);
		}
	}

	static void dumpAll(final WapiClient client)
	{
		dump(client, "", MAX_DEPTH);
	}

	/**
	 * Prints the definitions below a start node.
	 *
	 * @param startPath dotted node name, or an empty string for the top-level nodes; slashes are accepted as separators
	 * @param maxDepth number of levels to descend below the direct children of the start node
	 */
	static void dump(final WapiClient client, final String startPath, final int maxDepth)
	{
		final var name = startPath.replace('/', '.');
		final var start = name.isEmpty() ? null : new WapiToken(name);
		collectChildren(client, start, name, new TraversalState(), 0, maxDepth);
	}

	/** @param parent node whose children are printed, or {@code null} for the top-level nodes */
	private static void collectChildren(final WapiClient client, final WapiToken parent, final String parentName,
			final TraversalState state, final int depth, final int maxDepth)
	{
		final List<WapiNodeDefinition> children;
		try
		{
			children = parent == null ? client.getNodeDefinitions() : client.getNodeDefinitions(parent);
		}
		catch (final WapiException e)
		{
			LOGGER.info("{}<error: {}>", indent(depth), e.getMessage());
			return;
		}
		for (final var definition : children)
		{
			if (!state.visitedHashes.add(definition.hash()))
			{
				continue;
			}
			if (state.definitionCount >= MAX_DEFINITIONS)
			{
				throw new WapiException("Node-description tree exceeds maximum size " + MAX_DEFINITIONS);
			}
			state.definitionCount++;
			final var fullName = parentName.isEmpty() ? definition.pathSegment() : parentName + "." + definition.pathSegment();
			printDefinition(definition, fullName, depth);

			if (definition.nodeKind() == WapiNodeKind.NODE && depth < maxDepth)
			{
				collectChildren(client, new WapiToken((int) definition.hash()), fullName, state, depth + 1, maxDepth);
			}
		}
	}

	private static void printDefinition(final WapiNodeDefinition definition, final String fullName, final int depth)
	{
		final var indent = indent(depth);
		final var line = new StringBuilder(indent)
				.append(label(definition))
				.append(" [")
				.append(definition.nodeKind())
				.append(", flags=0x")
				.append(String.format("%04X", definition.flags()))
				.append(", hash=0x")
				.append(String.format("%08X", definition.hash()))
				.append("]");
		if (definition.longName() != null && !definition.longName().isEmpty())
		{
			line.append(" - ").append(definition.longName());
		}
		final long nameHash = Integer.toUnsignedLong(new WapiToken(fullName).hash());
		if (nameHash != definition.hash())
		{
			line.append(" (hash of \"").append(fullName).append("\" is 0x").append(String.format("%08X", nameHash)).append(')');
		}
		LOGGER.info("{}", line);

		final var metadata = definition.metadata();
		if (metadata != null)
		{
			if (!metadata.enumItems().isEmpty())
			{
				LOGGER.info("{}  enum={}", indent, metadata.enumItems());
			}
			if (metadata.minFloat() != null)
			{
				LOGGER.info("{}  range={}..{}, steps={}", indent, metadata.minFloat(), metadata.maxFloat(), metadata.steps());
			}
			if (metadata.minInt() != null)
			{
				LOGGER.info("{}  range={}..{}", indent, metadata.minInt(), metadata.maxInt());
			}
			if (metadata.maxStringLength() != null)
			{
				LOGGER.info("{}  maxStringLength={}", indent, metadata.maxStringLength());
			}
		}
		LOGGER.info("{}  raw={}", indent, toHex(definition.rawBytes()));
	}

	/** {@return the path segment, followed by the name when the node is addressed by number but also has a name} */
	private static String label(final WapiNodeDefinition definition)
	{
		final var segment = definition.pathSegment();
		final var name = definition.name();
		return name == null || name.isEmpty() || name.equals(segment) ? segment : segment + " (" + name + ")";
	}

	private static String indent(final int depth)
	{
		return "  ".repeat(depth);
	}

	private static String toHex(final byte[] bytes)
	{
		return HexFormat.of().formatHex(bytes);
	}

	private static final class TraversalState
	{
		private final Set<Long> visitedHashes = new HashSet<>();
		private int definitionCount;
	}
}
