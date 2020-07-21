/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.coordinator.group

import java.util

import kafka.utils.nonthreadsafe

class Test {

  def main(args: Array[String]): Unit = {
    println(s"hello world")

  }
}

/**
 * 组成员概要数据，提取了最核心的元数据信息。
 *
 * @param memberId        成员id, 由kafka 自动何时生成 consumer-组id-序号-
 * @param groupInstanceId consumer端, group.instance.id 消费者组静态成员id
 * @param clientId        消费者组成员配置的 client.id 参数
 * @param clientHost      consumer 端程序主机名
 * @param metadata        消费者组成员使用的分配策略, 标识消费者组成员分区分配策略的字节数组，
 *                        由消费者端参数 partition.assignment.strategy 值设定，默认的 RangeAssignor 策略是按照主题平均分配分区。
 * @param assignment      成员订阅分区, 每个消费者组都要选出一个 Leader 消费者组成员，负责给所有成员分配消费方案。
 *                        之后，Kafka 将制定好的分配方案序列化成字节数组，赋值给 assignment，分发给各个成员。
 */
case class MemberSummary(memberId: String,
                         groupInstanceId: Option[String],
                         clientId: String,
                         clientHost: String,
                         metadata: Array[Byte],
                         assignment: Array[Byte])

private object MemberMetadata {
  // 由于消费者组下面的成员可能采取了不同的 partition.assignment.strategy , 所以这里是个数组
  def plainProtocolSet(supportedProtocols: List[(String, Array[Byte])]) = supportedProtocols.map(_._1).toSet
}

/**
 * Member metadata contains the following metadata:
 *
 * Heartbeat metadata:
 * 1. negotiated heartbeat session timeout
 * 2. timestamp of the latest heartbeat
 *
 * Protocol metadata:
 * 1. the list of supported protocols (ordered by preference)
 * 2. the metadata associated with each protocol
 *
 * In addition, it also contains the following state information:
 *
 * 1. Awaiting rebalance callback: when the group is in the prepare-rebalance state,
 * its rebalance callback will be kept in the metadata if the
 * member has sent the join group request
 * 2. Awaiting sync callback: when the group is in the awaiting-sync state, its sync callback
 * is kept in metadata until the leader provides the group assignment
 * and the group transitions to stable
 *
 * 参数:
 * @param rebalanceTimeoutMs rebalanceTimeoutMs：Rebalance 操作的超时时间，即一次 Rebalance 操作必须在这个时间内完成，
 *                           否则被视为超时。这个字段的值是 Consumer 端参数 max.poll.interval.ms 的值
 * @param sessionTimeoutMs 会话超时时间。当前消费者组成员依靠心跳机制“保活”。如果在会话超时时间之内未能成功发送心跳，
 *                         组成员就被判定成“下线”，从而触发新一轮的 Rebalance。
 *                         这个字段的值是 Consumer 端参数 session.timeout.ms 的值。
 * @param protocolType 直译就是协议类型。它实际上标识的是消费者组被用在了哪个场景。这里的场景具体有两个：
 *                     第一个是作为普通的消费者组使用，该字段对应的值就是 consumer；
 *                     第二个是供 Kafka Connect 组件中的消费者使用，该字段对应的值是 connect。
 *                     当然，不排除后续社区会增加新的协议类型。
 * @param supportedProtocols 标识成员配置的多组分区分配策略。
 *                           目前，Consumer 端参数 partition.assignment.strategy 的类型是 List，
 *                           说明你可以为消费者组成员设置多组分配策略
 *                           因此，这个字段也是一个 List 类型，每个元素是一个元组（Tuple）。
 *                           元组的第一个元素是策略名称，第二个元素是序列化后的策略详情
 */
@nonthreadsafe
private[group] class MemberMetadata(var memberId: String,
                                    val groupId: String,
                                    val groupInstanceId: Option[String],
                                    val clientId: String,
                                    val clientHost: String,
                                    val rebalanceTimeoutMs: Int,
                                    val sessionTimeoutMs: Int,
                                    val protocolType: String,
                                    var supportedProtocols: List[(String, Array[Byte])]) {
  // 保存分配给该成员的分区分配方案
  var assignment: Array[Byte] = Array.empty[Byte]
  // 表示组成员是否正在等待加入组。
  var awaitingJoinCallback: JoinGroupResult => Unit = null
  // 表示组成员是否正在等待 GroupCoordinator 发送分配方案。
  var awaitingSyncCallback: SyncGroupResult => Unit = null
  // 表示组成员是否发起“退出组”的操作。
  var isLeaving: Boolean = false
  // 表示是否是消费者组下的新成员。
  var isNew: Boolean = false
  // 是否为静态成员
  val isStaticMember: Boolean = groupInstanceId.isDefined

  // This variable is used to track heartbeat completion through the delayed
  // heartbeat purgatory. When scheduling a new heartbeat expiration, we set
  // this value to `false`. Upon receiving the heartbeat (or any other event
  // indicating the liveness of the client), we set it to `true` so that the
  // delayed heartbeat can be completed.
  var heartbeatSatisfied: Boolean = false

  def isAwaitingJoin = awaitingJoinCallback != null

  def isAwaitingSync = awaitingSyncCallback != null

  /**
   * Get metadata corresponding to the provided protocol.
   */
  def metadata(protocol: String): Array[Byte] = {
    supportedProtocols.find(_._1 == protocol) match {
      case Some((_, metadata)) => metadata
      case None =>
        throw new IllegalArgumentException("Member does not support protocol")
    }
  }

  def hasSatisfiedHeartbeat: Boolean = {
    if (isNew) {
      // New members can be expired while awaiting join, so we have to check this first
      heartbeatSatisfied
    } else if (isAwaitingJoin || isAwaitingSync) {
      // Members that are awaiting a rebalance automatically satisfy expected heartbeats
      true
    } else {
      // Otherwise we require the next heartbeat
      heartbeatSatisfied
    }
  }

  /**
   * Check if the provided protocol metadata matches the currently stored metadata.
   */
  def matches(protocols: List[(String, Array[Byte])]): Boolean = {
    if (protocols.size != this.supportedProtocols.size)
      return false

    for (i <- protocols.indices) {
      val p1 = protocols(i)
      val p2 = supportedProtocols(i)
      if (p1._1 != p2._1 || !util.Arrays.equals(p1._2, p2._2))
        return false
    }
    true
  }

  def summary(protocol: String): MemberSummary = {
    MemberSummary(memberId, groupInstanceId, clientId, clientHost, metadata(protocol), assignment)
  }

  def summaryNoMetadata(): MemberSummary = {
    MemberSummary(memberId, groupInstanceId, clientId, clientHost, Array.empty[Byte], Array.empty[Byte])
  }

  /**
   * Vote for one of the potential group protocols. This takes into account the protocol preference as
   * indicated by the order of supported protocols and returns the first one also contained in the set
   */
  def vote(candidates: Set[String]): String = {
    supportedProtocols.find({ case (protocol, _) => candidates.contains(protocol) }) match {
      case Some((protocol, _)) => protocol
      case None =>
        throw new IllegalArgumentException("Member does not support any of the candidate protocols")
    }
  }

  override def toString: String = {
    "MemberMetadata(" +
      s"memberId=$memberId, " +
      s"groupInstanceId=$groupInstanceId, " +
      s"clientId=$clientId, " +
      s"clientHost=$clientHost, " +
      s"sessionTimeoutMs=$sessionTimeoutMs, " +
      s"rebalanceTimeoutMs=$rebalanceTimeoutMs, " +
      s"supportedProtocols=${supportedProtocols.map(_._1)}, " +
      ")"
  }
}
