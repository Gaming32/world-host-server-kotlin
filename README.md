# World Host Server

This is the server software for [World Host](https://github.com/Gaming32/world-host) that manages a list of online players and communications between them. It also serves as a proxy server for when UPnP is not available<!-- (see the World Host README for more information) -->.

## Proxy server

For the proxy server to work, `--baseAddr` needs to be passed, and a wildcard domain needs to be set up. For example, if `wh.example.com` is passed, there needs to be a CNAME for `*.wh`.

## Analytics

Basic analytics about how many players are online as well as how many players are from each country are written to `analytics.csv` while the server is running. Information will be flushed to this file with the period specified with `--analyticsTime` (default every 10 minutes). `--analyticsTime 0s` will disable analytics.
