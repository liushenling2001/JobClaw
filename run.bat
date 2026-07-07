@echo off
set "NO_PROXY=localhost,127.0.0.1,::1,192.168.0.4"
set "no_proxy=%NO_PROXY%"
java -Dhttp.nonProxyHosts="localhost|127.*|[::1]|192.168.0.4" -Dhttps.nonProxyHosts="localhost|127.*|[::1]|192.168.0.4" -Xms2g -Xmx6g -jar ./target/jobclaw-1.0.0.jar gateway
