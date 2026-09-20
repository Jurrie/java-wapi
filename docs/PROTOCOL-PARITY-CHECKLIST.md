# Protocol parity checklist

Known gaps between this library and the PDF (`WING Remote Protocols.pdf`) or the C wapi library (`java-wapi-linux/`). Remove an item when it is fixed and covered by a test.

## Protocol issues

- **Tree navigation is not resolved.** `WapiEventDecoder` consumes supported navigation tokens, value tokens and node-definition responses at their protocol boundaries, including across chunks, but does not resolve root, parent, node-index or node-name navigation to event tokens. Only hash-addressed updates produce events.
- **Unsolicited events can end up in a `wGetBinaryNode` node response.** The console answers a node with a flat list of records for every parameter below it, ended by `0xDE` (confirmed with captures). Because those records carry other hashes than the requested node, an unsolicited event that arrives between the request and the `0xDE` cannot be told apart and becomes part of the response. The reverse also happens: a reply of only `0xDE` means the console does not know the token (`WNODE`, as in C), but a `0xDE` left over from an earlier reply, whose end arrived in a later packet, looks the same. `wGetNode` passes records of registered parameters outside the requested node on as events; records with an unregistered hash still end up in the response. The background reader thread does not change this: the ambiguity is in the byte stream itself.

## Missing or different C functions

- **`wGetNode`** and **`wGetNodeToTVArray`** take parameter names from the C token table. Java only knows the names in the token registry, which `getNodeDefinitions` fills for the nodes it is asked about: `wGetNode` writes other parameters as their hash (`0x401E6CF6=29.08`), followed by a name from the root, and `wGetNodeToTVArray` returns them with a token without a name. C also rejects a parameter token before sending; Java finds out from the reply. Both return `WNODE`.
- **Signatures.** The functions keep their C names but have Java signatures: `void` instead of a status code (errors are exceptions with the C code), a `List` instead of an output array for `wGetParsedEvents`, no buffer size for `wGetBinaryNode`/`wGetBinaryData`, an `Optional` instead of `null`, and overloads with a `Duration` instead of the `...Timed` functions. `WapiClient` is `AutoCloseable`.
- **Reading.** C only reads inside a call, and its one-shot reads discard the events received while waiting (PDF, after `wGetTokenDef`). Java reads on a background reader thread: events reach the listeners as soon as they arrive and are queued for `wGetParsedEvents` (the newest 10,000), and the client sends the keep-alive by itself. Requests are sent one at a time.
- **Lost connection.** C only notices a lost connection in the next call that fails. Java finds it by itself and calls the listeners of `addConnectionListener`, which C does not have; see `docs/WAPI-API.md`. The client does not reconnect by itself.
  - Java turns on TCP keep-alive with `TCP_KEEPIDLE` 1 s, `TCP_KEEPINTERVAL` 1 s and `TCP_KEEPCOUNT` 3 where the JDK supports these timings; otherwise the system defaults apply (2 h on Linux).
  - A missing keep-alive reply is reported as `WRECV_ERROR`. C has no such check; the code is an assumption, chosen because nothing was received.
- **Meters.** C receives a meter report inside `wGetMeters`, and the application renews each request with `wRenewMeters` at least every 5 seconds. Java receives the reports on a background thread, queues the newest 1,000 for `wGetMeters`, and calls the listeners of `addMeterListener`, which C does not have. It renews every request sent with `wSetMetersRequest` every 4 seconds by itself, and sends the requests again after a reconnect; `wStopMeters`, which C does not have, stops renewing one.

## Out of scope

Differences from the C library that are deliberately not implemented.

- **`wKeepAlive`.** In C, the application calls it at least every 10 seconds. Java sends the keep-alive by itself on its background reader, so the function is not needed and does not exist.
- **`wOpen("")`** searches for a console in C: a broadcast on the /24 of the local network, then a connection to the first console that answers (PDF "wOpen"). Java throws instead. `WapiDiscovery.discoverLocal` broadcasts on the broadcast address of every local network interface and returns every console that answers, so the caller can choose one and connect with `WapiConsoleInfo.toSession()`.

- **Token table.** C has an enum of about 39,000 tokens plus `wGetName`, `wGetHash` and `wGetType`. Java has no token table, so callers must know the native names, values are not converted to the token's type before sending, and `wSetNode` guesses value types from the text.

## Console behaviour that differs from the PDF

- **No events before the client has sent something.** The PDF does not say so, but a console sends no events on a new connection until it has received data from the client: with a client that sent nothing for the first 6 s, clicks on a USER button in those seconds were lost, and later clicks arrived (seen 2026-10-13). Java therefore sends the keep-alive right after connecting (since 2026-10-13), and on the console the first click right after connecting now arrives. Not verified: whether the channel selector `df d1` alone is enough.

- **`wGetBinaryData` with a leading slash does not work.** The PDF's examples are `"/ch"`, `"/ch.1.eq"` and `"/ch.1.name"`, but C hashes the name as given, slash included, and the console does not know that hash: C `wGetBinaryData("/ch.1.flt")` returns -1, `wGetBinaryData("ch.1.flt")` returns the node's data (captured with libwapi.a). Java does the same, so use names without the slash.
- **The long node index `0xD2` is not used as documented.** A console never sent `0xD2` in any capture: replies, node data and events all address parameters by hash. Navigating with it in requests did not work either. After `root, hash of $ctl.layer.L.$spill` (a node with 76 numbered children), the short form `0x40..0x7F` reached slots 1 and 64 as documented, but `0xD2` followed by a word did not reach any slot:

  | Word after `0xD2` | Reply to a definition request |
  |---|---|
  | `0000`, `0001`, `0100`, `4000` | the definitions of `$spill` itself, as if `0xD2` were not there |
  | `0040`, `004b`, `004c` | empty (`0xDE` only) |

  So the decoder's reading of a received `0xD2` (the word holds index - 1, after the PDF's range 1..65536) cannot be verified, and it does not matter in practice. `WapiRawRequestExample` does not offer `0xD2`, because the bytes after it cannot be guaranteed to be read as an index.

## Known emulator differences

The WING emulator is not a reliable reference for everything; prefer the PDF and captures from a console.

- **Meter reports.** For `mon,ch27,ch28,fx1,dca1` the emulator sent 31 values instead of 36, and for `ch28v2` 9 instead of 11, filled with a repeating test pattern regardless of the requested groups. A console sends the lengths from PDF Table 5. The emulator does accept the same requests and answers them.
