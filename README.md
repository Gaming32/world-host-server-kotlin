# World Host Server

This is the server software for [World Host](https://github.com/Gaming32/world-host) that manages a list of online players and communications between them. It also serves as a proxy server for when UPnP is not available<!-- (see the World Host README for more information) -->.

## Proxy server

For the proxy server to work, `--baseAddr` needs to be passed, and a wildcard domain needs to be set up. For example, if `wh.example.com` is passed, there needs to be a CNAME for `*.wh`.

## Analytics

Basic analytics about how many players are online as well as how many players are from each country are written to `analytics.csv` while the server is running. Information will be flushed to this file with the period specified with `--analyticsTime` (default every 10 minutes). `--analyticsTime 0s` will disable analytics.

## Configuring
```
-p --port          : What port the server will bind to
-P --punchPort     : What the punch server will bind to (0 to disable)
-a --baseAddr      : What address the server will use for proxy connections
-j --inJavaPort    : Port to use for incoming java connections
-J --exJavaPort    : Port to use for outgoing java connections
   --analyticsTime : Amount of time between analytics updates
   --shutdownTime  : Amount of time before the server automatically shuts down
```

## Docker Instructions

Before we run the server you will have to build the world host server image by doing the following command, this might take a while.
```
$ docker build -t "world-host-server" .
```
After the image is built you want to find a place to put the container in, personally I will do it in `~/docker/world-host-server`. In the directory of your choosing make a `compose.yml` file which should look something like this.
```yaml
services:
  world-host-server:
    image: world-host-server
    container_name: world-host-server
    ports:
      - "25565:25565"
      - "9646:9646"
      - "9746:9746"
    command: java -jar world-host-server.jar -P 9746 -a example.com
    restart: always
```
To start the server just run the following command. To change settings read the section about configuring and make those changes to `command: ___` in your `config.yml`.
```
$ docker compose up
```
