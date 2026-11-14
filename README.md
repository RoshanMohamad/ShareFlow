# ShareFlow

Java NIO local-network file sharing: a headless single-threaded NIO server and a JavaFX client.
Protocol and design are described in [claude.md](claude.md).

## Requirements
- JDK 17 or newer (tested on JDK 25), Maven 3.9+
- Allow Java through the firewall for TCP 5000 and UDP 5001 on the server machine

## Build
```
mvn clean compile
mvn test          # framing tests + loopback server integration tests
```

## Run the server
```
mvn exec:java                              # port 5000, folder ./shared
mvn exec:java -Dexec.args="5000 LabServer shared"
```
The server prints its LAN IP addresses, joins, uploads and downloads.

## Run the client
```
mvn javafx:run
```
The connect screen scans for servers by UDP broadcast; you can also type the IP, port and a username.

## Layout
- `common` – frame format (`FrameCodec`), message types, payload helpers
- `server` – `EventLoop` (one `Selector`), `UploadHandler`, `DownloadHandler`, `FileRepository`, `ClientRegistry`, `Broadcaster`, `DiscoveryResponder`
- `client` – `ClientConnection`, `UploadTask`, `DownloadTask`, `DiscoveryClient`, JavaFX `ui`
