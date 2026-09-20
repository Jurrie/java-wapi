# WAPI API Notes

This document describes the Java API in terms of the C wapi library. Known differences from the C library are listed in `PROTOCOL-PARITY-CHECKLIST.md`.

## Layering

1. Channel framing: `WapiNativeChannelCodec`
2. Binary stream tokens: `WapiBinaryStreamCodec`, `WapiEncoder`, `WapiEventDecoder`
3. Node definitions: `WapiNodeDefinitionCodec`, `WapiNodeTree`
4. Transport: `WapiTransport`, `TcpWapiTransport`
5. Client: `WapiClient`, with the C function names and Java signatures. Behind it, package-private classes in `client` do the work:
   - `Connection`: the transport, the reader thread, requests and the routing of their replies, sending, and a failed or lost connection;
   - `EventDelivery`: the event queue, and the event and connection listeners with their dispatcher thread;
   - `KeepAlive`: the keep-alive after connecting and when the connection has been quiet;
   - `Meters`: the UDP meter reader, the meter listeners with their dispatcher thread, and the renewal of meter requests.

## C names, Java signatures

`WapiClient` keeps the names of the C functions, but its signatures follow Java:

- Calls that return only a status in C (`wOpen`, `wClose`, the setters, `wSetNode`, `wSetMetersRequest`, ...) return `void`. A normal return means success; an error is a `WapiException` with the C error code in `getError()`.
- Results are returned, not written into a caller's buffer: `wGetParsedEvents` returns a `List<WapiEvent>`, and `wGetBinaryNode` and `wGetBinaryData` return the whole reply without a buffer size.
- "Not found" and "nothing arrived in time" are an `Optional` instead of `null`: `wGetTokenFromHash`, `wGetNameFromHash`, `wGetMeters`.
- The C `...Timed` functions are overloads with a `Duration`: `wGetTokenFloat(token, timeout)` instead of `wGetTokenFloatTimed(token, timeout)`, and `wGetParsedEvents(timeout)` instead of `wGetParsedEventsTimed`.
- `WapiClient` is `AutoCloseable`: `close()` does what `wClose()` does.

## Connecting

```java
try (var client = new WapiClient(new WapiSession("192.168.1.9", 2222), new TcpWapiTransport())) // connects
{
	// ...
} // closes, as wClose()
```

To add listeners and token names before any event arrives, create the client unconnected and connect with `wOpen`, as in C:

```java
try (var client = new WapiClient(new TcpWapiTransport())) // not connected yet
{
	client.getTokenRegistry().register(new WapiToken("ch.1.mute"));
	client.addEventListener(event -> System.out.println(event.token().name() + " = " + event.value()));
	client.wOpen("192.168.1.9");
	// ...
}
```

With discovery, the flow is: discover, create the client, register names and listeners, connect. `wOpen(console)` connects to the address the console's reply came from:

```java
WapiConsoleInfo console = WapiDiscovery.discoverLocal(WapiDiscovery.DEFAULT_TIMEOUT).get(0);
try (var client = new WapiClient(new TcpWapiTransport()))
{
	client.addEventListener(event -> System.out.println(event.token().name() + " = " + event.value()));
	client.wOpen(console);
	// ...
}
```

While connected, the client reads on a background reader thread; this differs from C, which only reads inside a call. When the console closes the connection, `isConnected()` returns false and later calls report "not connected"; a call that is waiting for a reply fails at once. `isConnected()`, which C does not have, is true while the transport is connected and the reader thread runs.

`wOpen(host)` opens a new connection to `host`, also when a connection is open; the old connection is closed first. `wOpen()` reconnects to the session host only when the connection is closed. Unlike C, `wOpen("")` does not search for a console; it throws. Use `WapiDiscovery` instead, which shows every console that answers:

```java
List<WapiConsoleInfo> consoles = WapiDiscovery.discoverLocal(WapiDiscovery.DEFAULT_TIMEOUT);
try (var client = new WapiClient(consoles.get(0).toSession(), new TcpWapiTransport())) // connects
{
	// ...
}
```

`WapiDiscovery` (package `client.discovery`) finds consoles without connecting:

```java
List<WapiConsoleInfo> consoles = WapiDiscovery.discover("192.168.1.0/24");   // broadcast to 192.168.1.255
List<WapiConsoleInfo> consoles = WapiDiscovery.discover("192.168.1.62");     // one address
List<WapiConsoleInfo> consoles = WapiDiscovery.discover("192.168.1.0/255.255.255.0", Duration.ofSeconds(2));
List<WapiConsoleInfo> consoles = WapiDiscovery.discoverLocal(WapiDiscovery.DEFAULT_TIMEOUT);
```

Discovery waits the whole timeout (1 s by default) and returns one `WapiConsoleInfo` per console in reply order. `address()` is the address the reply came from, which `toSession()` and `wOpen(console)` use; `consoleIp()` is the address the console reports, which is `0.0.0.0` for the emulator. Routers usually do not forward a broadcast into another subnet, so a subnet works when this machine is on it; a single address works anywhere.

Right after connecting, the client sends the keep-alive and waits up to 0.9 s for its reply. This matters: the console sends no events before it has received something from the client. On a console, clicks in the first seconds after connecting were lost while the client still waited 6 s for its first keep-alive. No reply in time is not an error.

WING closes a connection after 10 seconds without traffic. The client sends a keep-alive (a data request for `$syscfg.consolename`) by itself when nothing has been sent, or nothing has been received, for 6 seconds, and its reply is not delivered as an event. Unlike C, there is no `wKeepAlive()`: it is not needed.

### Lost connection

The client finds out by itself that the connection is gone; C only notices in the next call that fails. It counts as lost when:

- the console closes the connection;
- a read fails, such as when TCP keep-alive gives up: the socket probes after 1 s of silence, 1 s apart, and fails after 3 unanswered probes, so a console that is switched off or a cable that is pulled is found in about 4 s;
- the keep-alive gets no reply within 2 s. TCP keep-alive does not probe while sent data is unacknowledged, which is what this covers; the keep-alive goes out at least every 6 s;
- a protocol error (`WTOKEN`).

The client then disconnects, `isConnected()` returns false, the next call throws the cause once (a closed connection only reports "not connected"), and the connection listeners are called on the dispatcher thread, after the event listeners of the events that arrived before. The listeners are not called for the application's own `wClose` or `wOpen`.

The client does not reconnect by itself. A listener may call `wOpen()` to reconnect to the same address, or discover the console again and call `wOpen(console)`. Retry on a thread of your own rather than with `sleep` in the listener, which would hold up the dispatcher thread:

```java
ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor();
client.addConnectionListener(cause -> retries.schedule(() -> reconnect(client, retries), 1, TimeUnit.SECONDS));

static void reconnect(WapiClient client, ScheduledExecutorService retries)
{
    try
    {
        client.wOpen();
        // Re-read the values the application shows: changes made meanwhile were not sent as events
    }
    catch (WapiException e)
    {
        retries.schedule(() -> reconnect(client, retries), 2, TimeUnit.SECONDS);
    }
}
```

Where the JDK does not support the TCP keep-alive timings (`TCP_KEEPIDLE`, `TCP_KEEPINTERVAL`, `TCP_KEEPCOUNT`; Linux and macOS do, Windows not on Java 17), the system defaults apply, which take hours; the keep-alive reply then still finds a dead connection within about 8 s.

## Tokens

A `WapiToken` is identified by its native dotted name (`ch.1.mute`) and the 32-bit hash computed from it. Use the native names, not the C enum labels (`CH_1_MUTE`). The Java library has no equivalent of the C token enum.

`wGetTokenFromName`, `wGetTokenFromHash` and `wGetNameFromHash` look tokens up in the client's `WapiTokenRegistry`. Hash lookups only find tokens that were registered or read before, and return an empty `Optional` otherwise; an unknown hash is not turned into a name.

## Reading and writing values

The getters send the native request `0xD7 <hash> 0xDC` and wait for the matching `0xD7 <hash> <value>` record:

- `wGetToken(token, type)`, `wGetTokenInt(token)`, `wGetTokenFloat(token)`, `wGetTokenString(token)`: default timeout of 200 ms.
- The same with a `Duration` as last argument, such as `wGetTokenFloat(token, timeout)`, for C's `wGetTokenFloatTimed` and the other `...Timed` functions (C uses microseconds).

The value is converted to the requested type where possible. Unrelated records received while waiting are queued as events.

The setters `wSetTokenInt`, `wSetTokenFloat` and `wSetTokenString` send `0xD7 <hash>` followed by an int32 (`0xD4`), float32 (`0xD5`) or string (`0xD1`, or `0xD0` for an empty string) value. `wToggleTokenInt` sends the toggle token `0xD8`; `wClickTokenByte` sends the step token `0xD9` with its byte.

## Events

WING sends changes made on the console, or by other clients, without a request. `wGetParsedEvents` returns them in FIFO order, like C `wGetParsedEvents` and `wGetParsedEventsTimed`:

```java
for (var event : client.wGetParsedEvents(Duration.ofSeconds(1)))
{
	System.out.println(event.token().name() + " = " + event.value());
}
```

- `wGetParsedEvents()` waits until there is an event, and returns all queued events.
- `wGetParsedEvents(maxEvents)` returns at most that many; the rest stays queued for the next call.
- `wGetParsedEvents(timeout)` and `wGetParsedEvents(maxEvents, timeout)` wait at most the timeout and return an empty list if nothing arrived.

Timeouts are a `Duration`, where C uses microseconds. `Duration.ZERO` returns what is already there without waiting; a negative or `null` timeout throws `IllegalArgumentException`. To wait indefinitely, use `wGetParsedEvents` without a timeout.

Listeners registered with `addEventListener` receive the same events as soon as they arrive, without any call: the reader thread decodes them, and a dispatcher thread calls the listeners in arrival order. A listener may call the client, for example to read another value. An exception from a listener does not stop the other listeners or later events; it is logged as a warning, with the exception (see "Logging" in the README). An event goes to the listeners that are registered when it arrives. The queue for `wGetParsedEvents` keeps the newest 10,000 events and drops the oldest, so an application that only uses listeners does not need to empty it. The events that arrive in the same packet as a reply are delivered before the call that waited for the reply returns.

Register tokens in `client.getTokenRegistry()` to get named events. Events for unregistered hashes are delivered with an unnamed token. A name only applies to events decoded after it was registered, so register names before connecting.

What events look like on the wire, from captures with a console (fixtures in `WapiCapturedEventsTest`):

- Each event is a bare `0xD7 <hash> <value>` record, without the end-of-data token that ends a reply to a request.
- A change arrives as one frame with two records: the setting (`ch.8.mute`) and its read-only counterpart (`ch.8.$mute`), usually with the same value. So one change gives two events.
- Moving a fader gives a burst of about 130 frames per second, each with a `fdr` / `$fdr` pair. -∞ is sent as -144.0.
- Status values such as `$stat.ppm` change on their own now and then, without any action.
- Changes made with another remote client, such as WING Edit, arrive the same way.
- A client's own writes are not sent back to it. Writes with `0xD4` (`$ctl.user.U1.2.led`, `col`) and `0xD1` (`bu.name`, and the enum `bu.mode` written by its option name) were applied, as a data request right after showed, but no event arrived within 2 s (captured with `WapiWriteEchoExample`). The PDF says the same for OSC over UDP. So after a `wSet...` call, update your own state; do not wait for an event.

## Protocol errors

The decoder accepts only the tokens in PDF Table 2, in the places the protocol allows them. An unknown token (`0xE0..0xFF`), or a token that is not a value directly after `0xD7 <hash>`, means the stream can no longer be decoded reliably. The reader thread then disconnects the transport, and the next call that reads, or the call that is waiting for a reply, throws a `WapiException` with error `WTOKEN`. Later calls fail with "not connected" until `wOpen` connects again.

## Nodes

- `wSetNodeFromTVArray(List<WapiTokenValue>)` sends several values in one request.
- `wSetNode("/ch.1.fdr=4,mute=1,.2.fdr=0")` parses `name=value` pairs and sends them with `wSetNodeFromTVArray`. Names follow the PDF: a name is relative to the node of the previous one, `/` starts at the root and a leading `.` goes one level up, so this sets `ch.1.fdr`, `ch.1.mute` and `ch.2.fdr`. A string value with spaces or commas goes in quotes: `/ch.1.name="space name"`.
- `wGetNodeToTVArray(node)` requests the node from the console and returns the values of all parameters below it, in the order the console sends them. Tokens come from the token registry; unregistered parameters get a token without a name. A parameter token throws `WNODE`, as in C.
- `wGetNode(token)` requests the node from the console and formats the reply like C (`.lc=1,lcf=29.08,...,\n`). Names come from the token registry; unregistered parameters are written as their hash.
- `wGetTokenDef(token)` requests the definition of a parameter (`0xD7 <hash> 0xDD`). `str()` and `num()` are what C returns: the response from its `index` field on, followed by `0xDE` (15 bytes for `ch.1.fdr`). `rawPayload()` is the response as received, and `nodeDefinition()` its parsed form. For a node, which the console answers with its children, or when no definition arrives within 200 ms, it throws `WRECV_ERROR`, as C returns.
- `getNodeDefinitions(node)` has no C counterpart: it requests the definitions of the children of a node (`0xD7 <hash> 0xDD`), and `getNodeDefinitions()` those of the top-level nodes (`0xDA 0xDD`). A walk over them gives the name, type and range of every parameter; the full name of a child is the parent's name followed by `pathSegment()`. When the parent's name is known, the children's full names are registered in the token registry, so after a walk down to a node, `wGetNode` and `wGetNodeToTVArray` show real names for its values. A parameter token throws `WNODE`, as the console answers it with its own definition. `WapiNodeDescriptionDumpExample` shows such a walk.
- `wGetBinaryNode(token[, timeout])` requests a node or parameter and returns its raw `0xD7 <hash> <value>` records: one record for a parameter, and for a node one record for every parameter at any depth below it. `wSetBinaryNode(bytes)` can send them back. Unlike C there is no buffer size: the whole reply is returned. A reply of only `0xDE`, the console's answer for a token it does not know, throws `WNODE`, as C returns.
- `wGetBinaryData(name[, timeout])` is `wGetBinaryNode` for a name such as `ch.1.eq`. The name is hashed as given, so the PDF's form with a leading slash (`/ch.1.eq`) gives `WNODE`; C also fails on such a name (captured with libwapi.a). Unlike C, which returns the reply including `0xDE`, it returns the whole reply without the closing `0xDE`.

## Metering

Meter reports arrive over UDP. A listener gets each report as it arrives:

```java
client.addMeterListener(report -> System.out.println(report.requestId() + ": " + report.decibelValues()));
int port = client.wMeterUDPPort(0); // 0 = any free port
WapiMeterRequest request = new WapiMeterRequest(7, port)
	.addGroup(WapiMeterGroup.CHANNEL, 1, 2) // channels 1 and 2
	.addGroup(WapiMeterGroup.MONITOR);    // monitor takes no index
client.wSetMetersRequest(request);
// ... reports arrive about 20 times per second until:
client.wStopMeters(7);
```

`wMeterUDPPort` starts a background thread that receives the reports, skips malformed datagrams (with a warning in the log), and queues the newest 1,000 for `wGetMeters`, which C applications use to poll:

```java
Optional<WapiMeterData> report = client.wGetMeters(Duration.ofMillis(500));
```

`wGetMeters` returns the oldest queued report, or an empty `Optional` when the timeout expires; `Duration.ZERO` returns a queued report without waiting. The meter listeners run on a dispatcher thread of their own, so they and the event listeners do not hold each other up. A report contains the request ID followed by signed big-endian 16-bit values, in 1/256 dB for levels.

WING stops sending after 5 seconds without a renewal. Unlike C, where the application calls `wRenewMeters(requestId)`, the client renews every request sent with `wSetMetersRequest` by itself, every 4 seconds while connected. `wStopMeters(requestId)`, which C does not have, stops renewing one request; the reports then stop within 5 seconds. `wClose` stops all of them. After a reconnect with `wOpen`, the client sends the requests again in full. It is not known whether WING keeps a meter request across connections; sending it again works either way. `wRenewMeters` still exists and does no harm.

Confirmed with a console (fixtures in `WapiCapturedMeterReportsTest`):

- The values follow the requested groups in request order, not in group or channel number order, with the counts from PDF Table 5: 8 per channel, aux, bus, main or matrix, 11 for their V2 variants, 10 per FX processor, 4 per DCA and 6 for the monitor.
- The port, request ID and group definition can be sent in one message, and consecutive indexes can follow one group token.
- Reports arrive about 20 times per second; renewing every 4 seconds keeps them coming without a gap.
- -32768 (`0x8000`) means no signal.
- Only levels are in 1/256 dB. Gain values such as gate gain use their own scale (256 is full gain reduction), and LEDs and states are 0 or 1, so `WapiMeterData.decibelValues()` is only meaningful for levels.

`wSetMetersRequest(requestId, mask)` takes the 30-byte selection bitmask of C, sent to the port opened with `wMeterUDPPort`. `wSetMetersRequest(request)` takes a `WapiMeterRequest` built from `WapiMeterGroup` values (PDF Table 4) instead. Each group checks its own index range; monitor and RTA take no index. The bitmask has room for DCA 1–16, but Table 4 allows 1…8, so DCA 9–16 is rejected.

## Channel framing

The native stream has 14 channels (IDs `0..13`), selected by `0xDF` followed by `0xD0..0xDD`. Channel ID 1 carries audio engine requests, and channel ID 3 carries meter requests. Inside the data, a `0xDF` followed by a byte in `0xD0..0xDE` is sent as `0xDF 0xDE <byte>`; a trailing `0xDF` is followed by `0xDE`. On receive, `0xDF 0xDE` decodes to a literal `0xDF`.

The transport passes received bytes on unchanged. `WapiClient` decodes selectors and escapes incrementally, so escapes split across socket reads are handled, and it processes every frame in a received chunk. A chunk is not a message boundary; the decoders keep incomplete records until the next chunk arrives.

## Binary stream tokens

From the PDF (Table 2, Binary Stream Protocol Tokens):

| Token | Data | Meaning |
|---|---|---|
| `0x00`, `0x01` | | false / true |
| `0x02..0x3F` | | int 2..63 |
| `0x40..0x7F` | | node index 1..64 |
| `0x80..0xBF` | 1..64 bytes | string of 1..64 characters |
| `0xC0..0xCF` | 1..16 bytes | node name of 1..16 characters |
| `0xD0` | | empty string |
| `0xD1` | length byte + 1..256 bytes | string of 1..256 characters |
| `0xD2` | word | node index 1..65536 (never sent by a console; see the parity checklist) |
| `0xD3` | word | int16 |
| `0xD4` | long | int32 |
| `0xD5` | long | float32 |
| `0xD6` | long | raw float32 (0.0..1.0) |
| `0xD7` | long | node hash |
| `0xD8` | | click (toggle) |
| `0xD9` | byte | step |
| `0xDA` | | go to root node |
| `0xDB` | | one level up |
| `0xDC` | | data request |
| `0xDD` | | request node definition |
| `0xDE` | | end of data / definition request |
| `0xDF` | word | node definition response |
