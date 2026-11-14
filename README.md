# ShareFlow

Java NIO local-network file sharing: a headless single-threaded NIO server and a JavaFX client.
Protocol and design: see the project brief (`claude.md`, kept out of git).

## Requirements
- JDK 21 or newer (tested on JDK 25), Maven 3.9+
- Allow Java through the firewall for TCP 5050 and UDP 5051 on the server machine

## Build
```
mvn clean compile
mvn test          # framing tests + loopback server integration tests
```

## Run the server
```
mvn exec:java                              # port 5050, folder ./shared
mvn exec:java -Dexec.args="5050 LabServer shared"      # cmd / bash
mvn exec:java "-Dexec.args=5050 LabServer shared"      # PowerShell (quote the whole option)
```
Arguments are `[tcpPort] [serverName] [sharedDir]`; all are optional.
The server prints its LAN IP addresses, joins, uploads and downloads.

## Run the client
```
mvn javafx:run
```
The connect screen scans for servers by UDP broadcast; you can also type the IP, port and a username.

## Layout
- `common` – frame format (`FrameCodec`), message types, payload helpers, SHA-256 `Hashing`
- `server` – `EventLoop` (one `Selector`), `UploadHandler`, `DownloadHandler`, `FileRepository`, `ClientRegistry`, `Broadcaster`, `DiscoveryResponder`
- `client` – `ClientConnection` (java.net.Socket), `TransferManager` (3-thread pool), `UploadTask`, `DownloadTask`, `DiscoveryClient`, JavaFX `ui`
