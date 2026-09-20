package org.jurr.java.wapi.examples;

import java.lang.invoke.MethodHandles;

import org.jurr.java.wapi.node.WapiNodeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small protocol-focused example of node-kind decoding from node-definition flags: the unit is in
 * bits 0..3, the node kind in bits 4..7 and the read-only flag in bit 9 (PDF Table 3, Node Flags).
 * The flag values below were captured from a console for {@code ch.1.mute}, {@code ch.1.name} and
 * {@code ch.1.fdr}.
 */
public final class WapiNodeKindExample
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private WapiNodeKindExample()
	{
	}

	/**
	 * Runs the example.
	 *
	 * @param args not used
	 */
	public static void main(final String[] args)
	{
		final var integerFlags = 0x0040;
		final var stringFlags = 0x0070;
		final var faderFlags = 0x0031;

		LOGGER.info("0x0040 => {}", WapiNodeKind.fromFlags(integerFlags));
		LOGGER.info("0x0070 => {}", WapiNodeKind.fromFlags(stringFlags));
		LOGGER.info("0x0031 => {}, unit code {} (dB)", WapiNodeKind.fromFlags(faderFlags), (faderFlags & 0x000F));
	}
}
