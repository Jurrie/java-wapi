# WING WAPI Java Library

A Java implementation of the Behringer WING remote control library ("wapi"). It provides the features of the C wapi library on top of the WING native/binary TCP protocol described in `docs/WING Remote Protocols.pdf`.

## API style

- Public methods that correspond to a C function keep its name and behaviour, for example `wOpen`, `wGetTokenFloat`, `wSetTokenInt` and `wGetParsedEvents`, with Java signatures: `void` instead of a status code, results as a `List` or `Optional`, and overloads with a `Duration` instead of the C `...Timed` functions. See `docs/WAPI-API.md`.
- Everything else follows Java conventions.

The C reference (`wapi.h`, `wext.h`, `libwapi.a`) is in `java-wapi-linux/`. This directory is not pushed to GitHub to avoid possible licensing issues.

## Maven modules

- `java-wapi-core`: protocol constants and codecs, tokens and values, node definitions, meters and discovery, in these packages under `org.jurr.java.wapi`:
  - the root package: tokens, values, events, errors and the session;
  - `protocol`: channel framing and the binary token stream (encoder, event decoder);
  - `node`: node definitions, the node tree and node assignments;
  - `meter`: meter requests, groups and reports;
  - `discovery`: discovery replies and subnet broadcast addresses.
- `java-wapi-client`: `WapiClient` and the I/O, in these packages under `org.jurr.java.wapi`:
  - `client`: `WapiClient`, which reads on a background thread, and its event, connection and meter listeners and subscriptions;
  - `client.transport`: the transport interface, the TCP transport and its channel streams;
  - `client.meter`: the UDP meter receiver;
  - `client.discovery`: UDP console discovery.
- `java-wapi-examples`: the examples, including the read-only diagnostic tools, in the package `org.jurr.java.wapi.examples`. They are not part of the library jars. Their tests use the test classes of `java-wapi-client`, such as `ScriptedWapiTransport`, which `java-wapi-client` publishes as a test-jar.

## Android compatibility
The `java-wapi-core` and `java-wapi-client` modules are Android API 34 compatible. This is tested during build using the `AndroidCompatibilityTest` unit tests.

## Core classes

- `WapiNativeChannelCodec`: native channel framing and escape handling.
- `WapiBinaryStreamCodec`: binary stream token constants and helpers.
- `WapiEventDecoder`: decodes `0xD7`/hash/value records into `WapiEvent`s.
- `WapiNodeDefinitionCodec`: node-definition response parser.
- `WapiNodeTree`: node definitions linked by their parent hash.
- `WapiMeterRequest` / `WapiMeterData`: meter request encoding and meter report decoding.
- `WapiDiscovery` (client): UDP console discovery in a subnet, at an address, or on the local networks.
- `WapiClient`: the C-style client API.
- `WapiEvent`: one decoded token update, the Java counterpart of a C `wTV` record.

## Build

The project needs Java 17. The `.sdkmanrc` file selects Java 17 and Maven with [SDKMAN!](https://sdkman.io): with `sdkman_auto_env=true` they are used automatically in the project folder; otherwise run `sdk env` (and once `sdk env install`). The build stops with a clear message on an older JDK.

```bash
mvn test
```

`WingEmulatorIntegrationTest` talks to a live console or emulator. It is skipped unless you set its address with `-Dwing.host=<ip>` (and optionally `-Dwing.port=<port>`, default 2222), and it skips when that address cannot be reached.

## Example usage

```java
try (var client = new WapiClient(new WapiSession("192.168.1.9", 2222), new TcpWapiTransport())) // connects
{
	var fader = new WapiToken("ch.1.fdr");
	System.out.println(fader.name() + " = " + client.wGetTokenFloat(fader));
} // closes, as wClose()
```

Token names are the native dotted names (`ch.1.fdr`), not the C enum labels (`CH_1_FDR`).

## Examples

The examples are in the `java-wapi-examples` module. Each takes the console's address as its first argument; the usage is in its class comment.

These need a reachable WING console:

- `WapiUsageExample`: prints the changes of the channel 1 fader and mute.
- `WapiMuteControlExample`: writes; sets the channel 1 mute.
- `WapiCustomControlMonitorExample`: prints the events of the custom control buttons.
- `WapiUserControlClickExample`: writes; presses and releases the first custom USER button.
- `WapiUserControlListenerExample`: discovers the console, then prints the events of the first custom USER button with a listener only, and reconnects when the connection is lost.
- `WapiDiscoveryExample`: lists the consoles that answer the discovery request in a subnet, at an address or on the local networks.
- `WapiWriteEchoExample`: writes; writes one parameter, prints what the console sends back and writes the original value back.
- `WapiNodeDescriptionDumpExample`: read-only; prints node definitions with their raw flags and bytes.
- `WapiRawRequestExample`: read-only; sends one data or definition request and prints the raw reply.
- `WapiRawListenExample`: read-only; listens and prints everything the console sends on its own, raw and decoded.
- `WapiMeterListenExample`: read-only; requests meters, such as one channel and the monitor, and prints every UDP meter report through a meter listener while the client renews the request.

These run without a console:

- `WapiNativeFrameExample`: encodes and decodes a native channel frame.
- `WapiNodeKindExample`: decodes node kinds from node-definition flags.

## Logging

The library logs through the [SLF4J](https://www.slf4j.org) API (`slf4j-api` 2.0), so an application chooses the logging framework by adding an SLF4J 2 provider, such as Logback or `slf4j-simple`. Without a provider, SLF4J prints a one-time notice and nothing is logged. The logger names are the class names, so the category `org.jurr.java.wapi` configures all of them.

The library keeps going in these cases, and logs them so that the cause can be found:

- `WARN`, with the exception: an event, connection or meter listener threw an exception. The other listeners, and later events or reports, are still delivered.
- `WARN`: a meter datagram that is not a valid meter report was skipped.
- `DEBUG`: a discovery reply that is not a WING console reply was skipped.

The examples log through `slf4j-jansi`, which writes to the console with the time and level in front of every line.

The tests use [slf4j-test](https://valfirst.github.io/slf4j-test/) to check what is logged. In `java-wapi-examples`, both `slf4j-jansi` and `slf4j-test` are on the test classpath; Maven selects `slf4j-test` with the system property `slf4j.provider`. When you run those tests outside Maven, for example from an IDE, add `-Dslf4j.provider=com.github.valfirst.slf4jtest.TestSLF4JServiceProvider` to the run configuration.

## Documentation

- `docs/WAPI-API.md`: the Java API in terms of the C library.
- `docs/WAPI-NODE-DEFINITION.md`: the node-definition response format.
- `docs/PROTOCOL-PARITY-CHECKLIST.md`: known gaps against the PDF and the C library.
