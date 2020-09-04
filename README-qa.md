# Q&A

## 问题

1. `kafka.common.InconsistentClusterIdException: The Cluster ID jrJ1h3cST6iTg3XRScXU5Q doesn't match stored clusterId Some(iHIJh4y0Sgq_V2EYub0CPw) in meta.properties`
    - 删除日志文件, 重新启动

2. 启动 manager
    - `docker run -d  -p 9001:9000 --name manager -e ZK_HOSTS="192.168.31.203:2181"  hlebalbau/kafka-manager:stable`


