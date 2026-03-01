# Lab 3: Multi-instance Paxos 实验报告

## 1. 实验概述

### 1.1 实验背景

Lab 2 的主备复制方案依赖单一的 ViewServer 来选择 Primary，如果 ViewServer 崩溃或网络不可达，整个服务将不可用。本实验通过实现 Paxos 共识协议来解决该问题：一组副本（replicas）使用 Paxos 协议对客户端请求的执行顺序达成一致，无需中心化的协调者。只要多数派服务器能够互相通信，系统就可以持续处理请求。

### 1.2 实验目标

1. **Multi-instance Paxos 协议**：实现完整的两阶段共识（Phase 1 Prepare/Promise, Phase 2 Accept/Accepted），每个日志槽位（slot）独立运行一个 Paxos 实例
2. **Stable Leader 机制**：通过心跳检测维持稳定领导者，减少不必要的领导者竞争
3. **垃圾回收机制**：当所有服务器都已执行某个 slot 的命令后，安全清除该 slot 的日志信息，防止内存无限增长
4. **接口方法**：实现 `status()`、`command()`、`firstNonCleared()`、`lastNonEmpty()` 四个查询方法，供测试框架验证正确性
5. **不可靠网络容错**：在消息可能丢失、乱序、重复的环境下保证协议正确性和活性
6. **单服务器 Paxos**：支持只有一个服务器的 Paxos 组高效运行（为 Lab 4 做准备）

### 1.3 最终结果

**27/27 测试通过，355/355 满分**，共修改/新增 **1075 行** Java 代码，分布在 11 个源文件中。

### 1.4 文件清单

| 文件路径 | 行数 | 说明 |
|----------|------|------|
| `labs/lab1-clientserver/src/dslabs/atmostonce/AMOCommand.java` | 17 | AMO 命令封装 |
| `labs/lab1-clientserver/src/dslabs/atmostonce/AMOResult.java` | 10 | AMO 结果封装 |
| `labs/lab1-clientserver/src/dslabs/atmostonce/AMOApplication.java` | 68 | AMO 幂等应用包装器 |
| `labs/lab1-clientserver/src/dslabs/kvstore/KVStore.java` | 91 | 键值存储应用 |
| `labs/lab3-paxos/src/dslabs/paxos/Messages.java` | 96 | Paxos 消息和数据结构定义 |
| `labs/lab3-paxos/src/dslabs/paxos/Timers.java` | 21 | 定时器定义 |
| `labs/lab3-paxos/src/dslabs/paxos/PaxosRequest.java` | 10 | 客户端请求消息 |
| `labs/lab3-paxos/src/dslabs/paxos/PaxosReply.java` | 10 | 服务器回复消息 |
| `labs/lab3-paxos/src/dslabs/paxos/PaxosClient.java` | 99 | Paxos 客户端 |
| `labs/lab3-paxos/src/dslabs/paxos/PaxosServer.java` | 641 | Paxos 服务器核心实现 |
| `labs/lab3-paxos/src/dslabs/paxos/PaxosLogSlotStatus.java` | 12 | 日志槽位状态枚举（已提供） |


---

## 2. 依赖模块详细设计（Lab 1）

Lab 3 依赖 Lab 1 中的 At-Most-Once（AMO）语义模块和 KVStore 应用。由于这些模块尚未实现，首先完成了它们的编写。

### 2.1 AMOCommand（17 行）

`AMOCommand` 对原始 `Command` 进行封装，添加了客户端标识和序列号，用于实现幂等性语义。

```java
@Data
public final class AMOCommand implements Command {
  private final Command command;       // 原始命令（如 Get/Put/Append）
  private final Address clientAddress; // 发送该命令的客户端地址
  private final int sequenceNum;       // 单调递增的序列号

  @Override
  public boolean readOnly() {
    return command.readOnly();  // 透传只读标记
  }
}
```

**设计说明**：
- `clientAddress + sequenceNum` 构成命令的唯一标识符。同一客户端的序列号单调递增，确保每个命令可被唯一识别
- `readOnly()` 方法透传底层命令的只读属性，便于后续优化（只读命令不需要写入状态机）

### 2.2 AMOResult（10 行）

```java
@Data
public final class AMOResult implements Result {
  private final Result result;    // 底层应用的实际执行结果
  private final int sequenceNum;  // 对应命令的序列号
}
```

**设计说明**：将执行结果与序列号绑定，客户端通过匹配序列号来确认响应对应的是哪一条命令。

### 2.3 AMOApplication（68 行）

`AMOApplication` 是实现 At-Most-Once 语义的核心。它包装任意 `Application` 实例，通过缓存每个客户端最近一次执行结果来防止命令被重复执行。

```java
public final class AMOApplication<T extends Application> implements Application {
  @Getter @NonNull private final T application;

  // 缓存：客户端地址 -> 该客户端最后一次执行的结果
  private final Map<Address, AMOResult> lastResults = new HashMap<>();
```

**核心方法 `execute()`**：

```java
public AMOResult execute(Command command) {
    AMOCommand amoCommand = (AMOCommand) command;
    Address clientAddress = amoCommand.clientAddress();
    int sequenceNum = amoCommand.sequenceNum();

    // 1. 检查是否已经执行过
    AMOResult lastResult = lastResults.get(clientAddress);
    if (lastResult != null && lastResult.sequenceNum() >= sequenceNum) {
      // 返回缓存结果（幂等性保证）
      return lastResult;
    }

    // 2. 首次执行：调用底层应用并缓存结果
    Result result = application.execute(amoCommand.command());
    AMOResult amoResult = new AMOResult(result, sequenceNum);
    lastResults.put(clientAddress, amoResult);
    return amoResult;
}
```

**执行流程**：
1. 从 `AMOCommand` 中提取客户端地址和序列号
2. 查找该客户端的缓存结果：如果缓存序列号 >= 当前序列号，说明该命令已执行过或是过时请求，直接返回缓存结果
3. 如果是新命令，调用底层 `application.execute()` 执行，缓存并返回结果

**辅助方法 `alreadyExecuted()`**：

```java
public boolean alreadyExecuted(AMOCommand amoCommand) {
    AMOResult lastResult = lastResults.get(amoCommand.clientAddress());
    return lastResult != null && lastResult.sequenceNum() >= amoCommand.sequenceNum();
}
```

该方法被 `PaxosServer` 频繁调用，用于在提议命令前快速判断是否需要执行，避免将已执行的命令再次放入 Paxos 日志。

### 2.4 KVStore（91 行）

`KVStore` 实现了一个简单的内存键值存储，支持三种操作：

```java
public class KVStore implements Application {
  private final Map<String, String> data = new HashMap<>();

  public KVStoreResult execute(Command command) {
    if (command instanceof Get) {
      String value = data.get(((Get) command).key());
      return value == null ? new KeyNotFound() : new GetResult(value);
    }
    if (command instanceof Put) {
      data.put(((Put) command).key(), ((Put) command).value());
      return new PutOk();
    }
    if (command instanceof Append) {
      String oldValue = data.get(((Append) command).key());
      String newValue = (oldValue == null ? "" : oldValue) + ((Append) command).value();
      data.put(((Append) command).key(), newValue);
      return new AppendResult(newValue);
    }
    throw new IllegalArgumentException();
  }
}
```

**操作语义**：
- **Get(key)**：返回 `GetResult(value)` 或 `KeyNotFound()`
- **Put(key, value)**：覆盖写入，返回 `PutOk()`
- **Append(key, value)**：将 value 追加到现有值末尾（key 不存在时等价于 Put），返回 `AppendResult(newValue)`

**`Get` 命令的 `readOnly()` 返回 true**，标记其为只读操作，这对 AMO 缓存和潜在的读优化有意义。


---

## 3. Paxos 消息与数据结构详细设计

### 3.1 Ballot 号（选票号）

```java
@Data
final class Ballot implements Comparable<Ballot>, Serializable {
  private final int seqNum;      // 选票序列号
  private final Address leader;  // 提出该选票的 leader 地址

  @Override
  public int compareTo(Ballot other) {
    if (this.seqNum != other.seqNum) {
      return Integer.compare(this.seqNum, other.seqNum);
    }
    return this.leader.compareTo(other.leader);  // 利用 Address 的自然序打破平局
  }
}
```

**设计说明**：
- Ballot 是 Paxos 协议的核心排序机制。每个 leader 尝试获取领导权时，会创建一个新的 Ballot（seqNum 递增）
- 比较规则：先比序列号，序列号相同则比较 leader 地址（`Address` 实现了 `Comparable`），确保全序
- 实现 `Serializable` 接口，因为 Ballot 会嵌入到消息中通过网络传输

### 3.2 PaxosLogEntry（日志条目）

```java
@Data
final class PaxosLogEntry implements Serializable {
  private final Ballot acceptedBallot;  // 接受该值时的 Ballot（可能为 null）
  private final AMOCommand command;     // 该 slot 存储的命令
  private final boolean chosen;         // 是否已达成共识

  public PaxosLogEntry asChosen() {
    return new PaxosLogEntry(acceptedBallot, command, true);
  }
}
```

**设计说明**：
- 利用角色合并的优势，每个 slot 只需存储一个 `PaxosLogEntry`，包含了 accepted 和 chosen 两种状态
- `asChosen()` 创建一个标记为 chosen 的副本，避免修改不可变对象（因为使用了 Lombok `@Data`）
- `acceptedBallot` 在 Decision 消息创建条目时可能为 null（Decision 只关心 command 本身）

### 3.3 协议消息

#### Phase 1 消息

```java
// Prepare 请求：Leader 向 Acceptor 发送，声明自己的 Ballot
@Data
final class P1a implements Message {
  private final Address sender;   // 发送者地址
  private final Ballot ballot;    // Leader 的 Ballot
}

// Promise 响应：Acceptor 向 Leader 回复，携带已接受的日志
@Data
final class P1b implements Message {
  private final Address acceptor;                      // 响应的 Acceptor 地址
  private final Ballot ballot;                         // 当前 Acceptor 见过的最高 Ballot
  private final boolean ok;                            // 是否接受该 Ballot
  private final Map<Integer, PaxosLogEntry> accepted;  // 已接受的 slot -> 日志条目
}
```

**P1b 的 `accepted` 字段**：包含该 Acceptor 上所有未清除的日志条目（无论 accepted 还是 chosen）。这是角色合并带来的优化：Leader 在 Phase 1 期间就能获知已 chosen 的值，加速选举过程。

#### Phase 2 消息

```java
// Accept 请求：Leader 要求 Acceptor 接受指定 slot 的值
@Data
final class P2a implements Message {
  private final Address sender;
  private final Ballot ballot;
  private final int slotNum;         // 目标 slot 编号
  private final AMOCommand command;  // 要写入该 slot 的命令
}

// Accepted 响应
@Data
final class P2b implements Message {
  private final Address acceptor;
  private final Ballot ballot;
  private final int slotNum;
  private final boolean ok;          // 是否接受（ballot 需要 >= acceptedBallot）
}
```

#### 心跳与垃圾回收消息

```java
// Leader 定期广播的心跳
@Data
final class Heartbeat implements Message {
  private final Address leader;
  private final Ballot ballot;
  private final int slotCleared;  // 全局已清除的最大 slot（GC 信息）
}

// Follower 对心跳的响应
@Data
final class HeartbeatReply implements Message {
  private final Address sender;
  private final Ballot ballot;
  private final int slotOut;  // 该 Follower 已执行到的 slot（即下一个待执行 slot）
}

// 决议通知：Leader 通知所有服务器某 slot 的值已 chosen
@Data
final class Decision implements Message {
  private final int slotNum;
  private final AMOCommand command;
}
```

**消息交互流程图**：

```
Client --PaxosRequest--> Leader
Leader --P1a-----------> Acceptors       (Phase 1: Prepare)
Acceptors --P1b--------> Leader          (Phase 1: Promise)
Leader --P2a-----------> Acceptors       (Phase 2: Accept)
Acceptors --P2b--------> Leader          (Phase 2: Accepted)
Leader --Decision------> All Servers     (通知共识结果)
Leader --PaxosReply----> Client          (回复客户端)
Leader --Heartbeat-----> Followers       (定期心跳)
Followers --HeartbeatReply--> Leader     (心跳响应+GC信息)
```

### 3.4 定时器

```java
@Data
final class ClientTimer implements Timer {
  static final int CLIENT_RETRY_MILLIS = 100;  // 客户端每 100ms 重试
}

@Data
final class HeartbeatCheckTimer implements Timer {
  static final int HEARTBEAT_CHECK_MILLIS = 100;  // 每 100ms 检查 Leader 心跳
}

@Data
final class HeartbeatTimer implements Timer {
  static final int HEARTBEAT_MILLIS = 25;  // Leader 每 25ms 发送心跳
}
```

**时间参数设计**：
- HeartbeatTimer (25ms) << HeartbeatCheckTimer (100ms)，确保在一个检查周期内 Leader 能发送多次心跳，降低因偶然丢包触发选举的概率
- 连续 2 次 HeartbeatCheckTimer（共 200ms）未收到心跳才发起选举，给予网络延迟足够的容忍窗口
- ClientTimer (100ms) 与 HeartbeatCheckTimer 周期相同，确保客户端在 Leader 切换时能及时重试


---

## 4. PaxosClient 详细设计（99 行）

### 4.1 状态变量

```java
public final class PaxosClient extends Node implements Client {
  private final Address[] servers;      // 所有服务器地址
  private int sequenceNum = 0;          // 当前序列号，每发送一个新命令递增
  private AMOCommand pendingCommand = null;  // 正在等待响应的命令
  private AMOResult result = null;      // 已收到的结果
```

### 4.2 发送命令

```java
public synchronized void sendCommand(Command operation) {
    sequenceNum++;
    pendingCommand = new AMOCommand(operation, address(), sequenceNum);
    result = null;

    // 广播到所有服务器（因为客户端不知道谁是 Leader）
    PaxosRequest request = new PaxosRequest(pendingCommand);
    broadcast(request, servers);

    // 启动重试定时器
    set(new ClientTimer(), CLIENT_RETRY_MILLIS);
}
```

**设计说明**：
- 客户端不追踪 Leader 身份，而是将请求广播到所有服务器。虽然增加了消息数量，但简化了客户端逻辑，避免了 Leader 切换时的复杂处理
- 每次新命令的序列号严格递增，保证 AMO 语义的正确性

### 4.3 处理回复

```java
private synchronized void handlePaxosReply(PaxosReply m, Address sender) {
    if (pendingCommand == null) return;  // 没有等待中的命令

    AMOResult amoResult = m.result();
    if (amoResult != null && amoResult.sequenceNum() == pendingCommand.sequenceNum()) {
      result = amoResult;
      pendingCommand = null;
      notify();  // 唤醒 getResult() 中等待的线程
    }
}
```

**关键细节**：
- 只接受与当前 pending 命令序列号匹配的回复，忽略过时的响应
- 收到正确回复后将 `pendingCommand` 置空，后续的重试定时器不会再发送请求

### 4.4 定时重试

```java
private synchronized void onClientTimer(ClientTimer t) {
    if (pendingCommand != null) {
      broadcast(new PaxosRequest(pendingCommand), servers);
      set(new ClientTimer(), CLIENT_RETRY_MILLIS);
    }
}
```

只要 `pendingCommand` 不为空（即尚未收到回复），就每隔 100ms 重新广播请求。这确保了在消息丢失或 Leader 切换时客户端不会永久阻塞。


---

## 5. PaxosServer 详细设计（641 行）

### 5.1 状态变量详解

PaxosServer 是系统的核心组件，同时扮演 Proposer/Acceptor/Learner/Replica 四种角色。其状态变量分为五组：

#### Acceptor 状态

```java
private Ballot acceptedBallot;                          // 已见过的最高 Ballot
private final Map<Integer, PaxosLogEntry> log = new HashMap<>();  // slot -> 日志条目
```

- `acceptedBallot`：Acceptor 承诺不再接受低于此 Ballot 的提案。所有收到的 P1a、P2a、Heartbeat 都可能更新此值
- `log`：核心数据结构，存储每个 slot 的 `PaxosLogEntry`。条目可能处于 accepted（尚未达成多数派共识）或 chosen（已达成共识）状态

#### Leader 状态

```java
private Ballot myBallot;                               // 我作为 Leader 使用的 Ballot
private boolean isLeader = false;                      // 当前是否为活跃 Leader
private final Set<Address> phase1Responses = new HashSet<>();      // Phase 1 已响应的 Acceptor 集合
private final Map<Integer, Set<Address>> phase2Responses = new HashMap<>();  // slot -> Phase 2 已响应集合
private final Map<Address, AMOCommand> pendingProposals = new HashMap<>();   // 等待提议的命令
private int nextSlotToPropose = 1;                     // 下一个可用的 slot 编号
```

- `phase1Responses`：记录在 Phase 1 期间哪些 Acceptor 已回复 P1b，达到多数派后成为 Leader
- `phase2Responses`：按 slot 记录哪些 Acceptor 已接受 P2a，每个 slot 达到多数派后标记为 chosen
- `pendingProposals`：非 Leader 服务器收到客户端请求时暂存于此，成为 Leader 后批量提议
- `nextSlotToPropose`：维护下一个空闲 slot 编号，避免每次都遍历日志查找空位

#### Replica 状态

```java
private int slotOut = 1;     // 下一个待执行的 slot
private int slotCleared = 0; // 已清除的最大 slot（GC 水位线）
```

- `slotOut`：顺序执行的游标。只有 slot `slotOut` 对应的命令被 chosen 后才能执行并推进
- `slotCleared`：所有 `<= slotCleared` 的 slot 都已被垃圾回收，其日志条目已从 `log` 中移除

#### Stable Leader 状态

```java
private Address currentLeader = null;  // 当前认定的 Leader 地址
private int missedHeartbeats = 0;      // 连续未收到心跳的次数
```

#### 垃圾回收状态

```java
private final Map<Address, Integer> serverSlotOuts = new HashMap<>();  // 各服务器的 slotOut
private int globalSlotCleared = 0;  // 所有服务器都已执行到的最小 slot
```

### 5.2 初始化

```java
public PaxosServer(Address address, Address[] servers, Application app) {
    super(address);
    this.servers = servers;
    this.app = new AMOApplication<>(app);  // 用 AMO 包装底层应用
    this.acceptedBallot = new Ballot(0, address);
    this.myBallot = new Ballot(0, address);
}

public void init() {
    set(new HeartbeatCheckTimer(), HEARTBEAT_CHECK_MILLIS);  // 启动心跳检测
    startPhase1();  // 尝试成为 Leader
}
```

**初始化策略**：每个服务器启动后立即尝试成为 Leader（发起 Phase 1）。由于 Ballot 的比较规则（先比序列号再比地址），具有最高地址的服务器会在初始选举中胜出。这保证了系统启动后能快速选出一个 Leader 来处理客户端请求。

### 5.3 Phase 1 详细流程（Prepare/Promise）

#### 5.3.1 发起 Phase 1（`startPhase1`）

```java
private void startPhase1() {
    // 1. 创建比已见最高 Ballot 更高的新 Ballot
    int newSeqNum = acceptedBallot.seqNum() + 1;
    myBallot = new Ballot(newSeqNum, address());
    acceptedBallot = myBallot;
    currentLeader = address();
    missedHeartbeats = 0;
    isLeader = false;

    // 2. 清除旧的选举状态
    phase1Responses.clear();
    phase2Responses.clear();

    // 3. 构造本地的 P1b 响应（不走网络，直接调用 handleP1b）
    Map<Integer, PaxosLogEntry> accepted = new HashMap<>();
    for (Map.Entry<Integer, PaxosLogEntry> entry : log.entrySet()) {
      if (entry.getKey() > slotCleared) {
        accepted.put(entry.getKey(), entry.getValue());
      }
    }
    P1b selfP1b = new P1b(address(), myBallot, true, accepted);
    handleP1b(selfP1b, address());

    // 4. 向其他服务器发送 P1a
    P1a p1a = new P1a(address(), myBallot);
    for (Address server : servers) {
      if (!server.equals(address())) {
        send(p1a, server);
      }
    }
}
```

**关键设计**：
- **本地 P1b 直接调用**：不通过网络发送给自己再处理，而是直接构造 P1b 并调用 `handleP1b()`。这对单服务器场景至关重要——单服务器的 majority 为 1，本地响应即可立即成为 Leader
- **新 Ballot 基于 `acceptedBallot`**：使用已见最高 Ballot 的 seqNum + 1，确保新 Ballot 一定更高
- **清除旧的 phase2Responses**：防止旧 Ballot 的 P2b 响应干扰新一轮选举

#### 5.3.2 处理 P1a（Acceptor 角色）

```java
private void handleP1a(P1a m, Address sender) {
    Ballot ballot = m.ballot();

    // 如果收到更高的 Ballot，更新状态
    if (ballot.compareTo(acceptedBallot) > 0) {
      acceptedBallot = ballot;
      currentLeader = ballot.leader();
      missedHeartbeats = 0;

      // 如果自己是 Leader 但被更高 Ballot 抢占，退位
      if (isLeader && !ballot.leader().equals(address())) {
        becomeFollower();
      }
    }

    // 回复 P1b：包含本地所有未清除的日志条目
    Map<Integer, PaxosLogEntry> accepted = new HashMap<>();
    for (Map.Entry<Integer, PaxosLogEntry> entry : log.entrySet()) {
      if (entry.getKey() > slotCleared) {
        accepted.put(entry.getKey(), entry.getValue());
      }
    }

    // ok = true 当且仅当 P1a 的 Ballot >= 当前 acceptedBallot
    boolean ok = ballot.compareTo(acceptedBallot) >= 0;
    send(new P1b(address(), acceptedBallot, ok, accepted), sender);
}
```

**关键细节**：
- 即使 `ok = false`（P1a 的 Ballot 已过时），仍然回复 P1b，让发送者知道存在更高的 Ballot
- P1b 中包含 `accepted` 和 `chosen` 的条目，利用了角色合并的优势

#### 5.3.3 处理 P1b（Leader 角色）

```java
private void handleP1b(P1b m, Address sender) {
    // 忽略非当前 Ballot 的响应
    if (!m.ballot().equals(myBallot)) return;

    if (!m.ok()) {
      // 被更高 Ballot 抢占
      if (m.ballot().compareTo(myBallot) > 0) {
        acceptedBallot = m.ballot();
        currentLeader = m.ballot().leader();
        becomeFollower();
      }
      return;
    }

    // 记录响应
    phase1Responses.add(m.acceptor());

    // 合并 Acceptor 返回的日志条目
    for (Map.Entry<Integer, PaxosLogEntry> entry : m.accepted().entrySet()) {
      int slot = entry.getKey();
      PaxosLogEntry their = entry.getValue();
      PaxosLogEntry ours = log.get(slot);

      if (their.chosen()) {
        log.put(slot, their);  // chosen 值直接采纳
      } else if (ours == null || !ours.chosen()) {
        // 未 chosen 时，选择 acceptedBallot 更高的值（Paxos 安全性保证）
        if (ours == null ||
            (their.acceptedBallot() != null &&
             (ours.acceptedBallot() == null ||
              their.acceptedBallot().compareTo(ours.acceptedBallot()) > 0))) {
          log.put(slot, their);
        }
      }

      if (slot >= nextSlotToPropose) {
        nextSlotToPropose = slot + 1;
      }
    }

    // 达到多数派：成为 Leader
    if (phase1Responses.size() >= majority()) {
      becomeLeader();
    }
}
```

**合并策略**：这是 Paxos 安全性的关键。在 Phase 1 期间，新 Leader 必须采纳已经被接受的值（而非自己想提议的值），具体规则：
1. 如果远端条目已 `chosen`，直接采纳（不可能被改变）
2. 如果双方都未 `chosen`，选择 `acceptedBallot` 更高的那个（它更可能已接近被 chosen）
3. 如果本地已 `chosen`，不会被覆盖

### 5.4 Phase 2 详细流程（Accept/Accepted）

#### 5.4.1 提议命令（`proposeCommand`）

```java
private void proposeCommand(AMOCommand command) {
    // 跳过已执行的命令
    if (app.alreadyExecuted(command)) {
      AMOResult result = app.execute(command);
      send(new PaxosReply(result), command.clientAddress());
      return;
    }

    // 去重：检查命令是否已在日志中
    for (Map.Entry<Integer, PaxosLogEntry> entry : log.entrySet()) {
      if (entry.getValue().command() != null && entry.getValue().command().equals(command)) {
        return;  // 已在某个 slot 中，等待其 chosen 即可
      }
    }

    // 找到下一个空闲 slot
    while (log.containsKey(nextSlotToPropose)) {
      nextSlotToPropose++;
    }

    int slot = nextSlotToPropose++;
    startPhase2(slot, command);
}
```

**三层保护**：
1. **AMO 检查**：命令已执行过，直接返回缓存结果
2. **日志去重**：命令已在某 slot 中（可能正在等待 Phase 2 完成），不重复提议，避免同一命令占用多个 slot
3. **空闲 slot 查找**：跳过已有条目的 slot，确保不覆盖

#### 5.4.2 发起 Phase 2（`startPhase2`）

```java
private void startPhase2(int slot, AMOCommand command) {
    // 本地记录 acceptance
    log.put(slot, new PaxosLogEntry(myBallot, command, false));
    phase2Responses.computeIfAbsent(slot, k -> new HashSet<>()).add(address());

    // 向其他服务器发送 P2a
    P2a p2a = new P2a(address(), myBallot, slot, command);
    for (Address server : servers) {
      if (!server.equals(address())) {
        send(p2a, server);
      }
    }

    // 单服务器优化：如果已达多数派，立即标记 chosen
    if (phase2Responses.get(slot).size() >= majority()) {
      log.put(slot, log.get(slot).asChosen());
      broadcastDecision(slot, command);
      executeCommands();
    }
}
```

**单服务器优化**：当 `servers.length == 1` 时，`majority() == 1`，Leader 自己的 acceptance 就构成多数派，无需等待网络消息。命令在单步内即可被 chosen 和执行。

#### 5.4.3 处理 P2a（Acceptor 角色）

```java
private void handleP2a(P2a m, Address sender) {
    Ballot ballot = m.ballot();
    int slot = m.slotNum();

    if (ballot.compareTo(acceptedBallot) > 0) {
      acceptedBallot = ballot;
      currentLeader = ballot.leader();
      missedHeartbeats = 0;
    }

    boolean ok = ballot.compareTo(acceptedBallot) >= 0;

    if (ok && slot > slotCleared) {
      PaxosLogEntry existing = log.get(slot);
      if (existing == null || !existing.chosen()) {
        log.put(slot, new PaxosLogEntry(ballot, m.command(), false));
      }
    }

    send(new P2b(address(), acceptedBallot, slot, ok), sender);
}
```

**安全性保证**：
- 只有当 P2a 的 Ballot >= acceptedBallot 时才接受值（`ok = true`）
- 已 chosen 的值不会被覆盖（即使收到更高 Ballot 的 P2a）
- 已清除的 slot（`<= slotCleared`）不会再写入

#### 5.4.4 处理 P2b（Leader 角色）

```java
private void handleP2b(P2b m, Address sender) {
    if (!m.ballot().equals(myBallot)) {
      if (m.ballot().compareTo(myBallot) > 0) {
        acceptedBallot = m.ballot();
        currentLeader = m.ballot().leader();
        becomeFollower();
      }
      return;
    }

    if (!m.ok()) return;

    int slot = m.slotNum();
    Set<Address> responses = phase2Responses.computeIfAbsent(slot, k -> new HashSet<>());
    responses.add(m.acceptor());

    // 多数派达成：标记 chosen 并广播 Decision
    if (responses.size() >= majority()) {
      PaxosLogEntry entry = log.get(slot);
      if (entry != null && !entry.chosen()) {
        log.put(slot, entry.asChosen());
        broadcastDecision(slot, entry.command());
      }
      executeCommands();
    }
}
```

### 5.5 命令执行

```java
private void executeCommands() {
    while (true) {
      PaxosLogEntry entry = log.get(slotOut);
      if (entry == null || !entry.chosen()) break;

      AMOCommand command = entry.command();
      if (command != null && !app.alreadyExecuted(command)) {
        AMOResult result = app.execute(command);
        send(new PaxosReply(result), command.clientAddress());
      }

      slotOut++;
    }

    // 更新自己的 slotOut（用于垃圾回收计算）
    if (isLeader) {
      serverSlotOuts.put(address(), slotOut);
      updateGlobalSlotCleared();
    }
}
```

**执行规则**：
- 严格按 slot 顺序执行，保证所有服务器以相同顺序执行命令（线性一致性基础）
- 使用 `app.alreadyExecuted()` 防止重复执行（同一命令可能出现在多个 slot 中）
- 执行后立即发送 `PaxosReply` 给客户端（所有执行了该命令的服务器都会发送回复，客户端只取第一个）
- 遇到未 chosen 的 slot 时停止执行（不能跳过空洞）

### 5.6 Stable Leader 机制

#### 5.6.1 成为 Leader

```java
private void becomeLeader() {
    if (isLeader) return;
    isLeader = true;

    // 1. 对 Phase 1 期间收集到的未 chosen 值重新发起 Phase 2
    for (Map.Entry<Integer, PaxosLogEntry> entry : log.entrySet()) {
      int slot = entry.getKey();
      PaxosLogEntry logEntry = entry.getValue();
      if (slot > slotCleared && !logEntry.chosen() && logEntry.command() != null) {
        startPhase2(slot, logEntry.command());
      }
    }

    // 2. 提议所有 pending 请求
    for (AMOCommand command : pendingProposals.values()) {
      if (!app.alreadyExecuted(command)) {
        proposeCommand(command);
      }
    }
    pendingProposals.clear();

    // 3. 启动心跳定时器
    set(new HeartbeatTimer(), HEARTBEAT_MILLIS);
    serverSlotOuts.put(address(), slotOut);
}
```

**关键步骤**：成为 Leader 后必须立即对 Phase 1 中发现的未 chosen 值重新发起 Phase 2。这是 Paxos 安全性要求——新 Leader 必须先完成前任 Leader 未完成的提案，才能开始提议新值。

#### 5.6.2 退位为 Follower

```java
private void becomeFollower() {
    isLeader = false;
    phase1Responses.clear();
    phase2Responses.clear();
    missedHeartbeats = 0;
}
```

#### 5.6.3 心跳定时器处理

```java
private void onHeartbeatTimer(HeartbeatTimer t) {
    if (isLeader) {
      // 广播心跳（携带 GC 信息）
      Heartbeat hb = new Heartbeat(address(), myBallot, globalSlotCleared);
      for (Address server : servers) {
        if (!server.equals(address())) send(hb, server);
      }

      // 重发未完成的 Phase 2 请求（应对消息丢失）
      resendPendingPhase2();

      set(new HeartbeatTimer(), HEARTBEAT_MILLIS);
    }
}
```

#### 5.6.4 心跳检查定时器处理

```java
private void onHeartbeatCheckTimer(HeartbeatCheckTimer t) {
    if (!isLeader) {
      missedHeartbeats++;

      if (missedHeartbeats >= 2) {
        startPhase1();  // 连续 2 次未收到心跳：发起选举
      } else if (phase1Responses.size() > 0 && phase1Responses.size() < majority()) {
        resendPhase1();  // Phase 1 进行中但未完成：重发 P1a
      }
    }

    set(new HeartbeatCheckTimer(), HEARTBEAT_CHECK_MILLIS);
}
```

### 5.7 垃圾回收机制

#### 5.7.1 心跳处理（Follower 端）

```java
private void handleHeartbeat(Heartbeat m, Address sender) {
    Ballot ballot = m.ballot();

    if (ballot.compareTo(acceptedBallot) >= 0) {
      acceptedBallot = ballot;
      currentLeader = m.leader();
      missedHeartbeats = 0;

      if (isLeader && !m.leader().equals(address())) {
        becomeFollower();
      }
    }

    // 根据 Leader 通知的 slotCleared 清除本地日志
    if (m.slotCleared() > slotCleared) {
      for (int i = slotCleared + 1; i <= m.slotCleared(); i++) {
        log.remove(i);
      }
      slotCleared = m.slotCleared();
    }

    // 回复自己的 slotOut
    send(new HeartbeatReply(address(), acceptedBallot, slotOut), sender);
}
```

#### 5.7.2 心跳回复处理（Leader 端）

```java
private void handleHeartbeatReply(HeartbeatReply m, Address sender) {
    if (!isLeader) return;

    serverSlotOuts.put(m.sender(), m.slotOut());
    updateGlobalSlotCleared();

    // 向落后的 Follower 补发缺失的 Decision
    for (int slot = m.slotOut(); slot < slotOut; slot++) {
      PaxosLogEntry entry = log.get(slot);
      if (entry != null && entry.chosen()) {
        send(new Decision(slot, entry.command()), sender);
      }
    }
}
```

#### 5.7.3 全局清除计算

```java
private void updateGlobalSlotCleared() {
    // 需要收到所有服务器的 slotOut 才能计算
    if (serverSlotOuts.size() < servers.length) return;

    int minSlotOut = Integer.MAX_VALUE;
    for (int s : serverSlotOuts.values()) {
      if (s < minSlotOut) minSlotOut = s;
    }

    int newGlobalCleared = minSlotOut - 1;
    if (newGlobalCleared > globalSlotCleared) {
      globalSlotCleared = newGlobalCleared;

      // 清除本地日志
      for (int i = slotCleared + 1; i <= globalSlotCleared; i++) {
        log.remove(i);
      }
      slotCleared = globalSlotCleared;
    }
}
```

**GC 完整流程**：
1. Leader 每 25ms 发送 `Heartbeat(ballot, globalSlotCleared)` 给所有 Follower
2. Follower 收到后清除 `<= slotCleared` 的日志，回复 `HeartbeatReply(slotOut)`
3. Leader 收集所有服务器的 `slotOut`，计算全局最小值 `minSlotOut`
4. `globalSlotCleared = minSlotOut - 1`（所有服务器都已执行到 minSlotOut，则 minSlotOut-1 及之前的 slot 可安全清除）
5. Leader 将新的 `globalSlotCleared` 放入下一次 Heartbeat 中传播给所有 Follower

### 5.8 接口方法

```java
public PaxosLogSlotStatus status(int logSlotNum) {
    if (logSlotNum <= slotCleared) return PaxosLogSlotStatus.CLEARED;
    PaxosLogEntry entry = log.get(logSlotNum);
    if (entry == null) return PaxosLogSlotStatus.EMPTY;
    if (entry.chosen()) return PaxosLogSlotStatus.CHOSEN;
    return PaxosLogSlotStatus.ACCEPTED;
}

public Command command(int logSlotNum) {
    if (logSlotNum <= slotCleared) return null;
    PaxosLogEntry entry = log.get(logSlotNum);
    if (entry == null) return null;
    return entry.command() != null ? entry.command().command() : null;
}

public int firstNonCleared() {
    return slotCleared + 1;
}

public int lastNonEmpty() {
    int maxSlot = slotCleared;  // CLEARED 状态算 non-empty
    for (int slot : log.keySet()) {
      if (slot > maxSlot) maxSlot = slot;
    }
    return maxSlot;
}
```

**`lastNonEmpty()` 的特殊处理**：初始化 `maxSlot = slotCleared` 而非 0，因为 CLEARED 状态不是 EMPTY 状态。即使 log 中没有任何条目（全部被 GC 清除），已清除的 slot 仍应被视为 non-empty。


---

## 6. 消息重试机制

在不可靠网络中，消息可能丢失。需要重试机制保证活性（liveness）。

### 6.1 Phase 1 重试

```java
private void resendPhase1() {
    P1a p1a = new P1a(address(), myBallot);
    for (Address server : servers) {
      if (!server.equals(address()) && !phase1Responses.contains(server)) {
        send(p1a, server);
      }
    }
}
```

**触发时机**：在 `onHeartbeatCheckTimer` 中，如果当前正在进行 Phase 1（`phase1Responses` 非空但未达多数派），则重发 P1a 给未响应的服务器。

### 6.2 Phase 2 重试

```java
private void resendPendingPhase2() {
    for (Map.Entry<Integer, PaxosLogEntry> entry : log.entrySet()) {
      int slot = entry.getKey();
      PaxosLogEntry logEntry = entry.getValue();

      if (slot <= slotCleared || logEntry.chosen() || logEntry.command() == null) continue;

      Set<Address> responded = phase2Responses.getOrDefault(slot, new HashSet<>());
      P2a p2a = new P2a(address(), myBallot, slot, logEntry.command());
      for (Address server : servers) {
        if (!server.equals(address()) && !responded.contains(server)) {
          send(p2a, server);
        }
      }
    }
}
```

**触发时机**：Leader 在每次心跳发送时调用，遍历所有未 chosen 的 slot，重发 P2a 给未响应的 Acceptor。频率为 25ms 一次，在不可靠网络中提供足够的重试密度。


---

## 7. 调试过程与问题修复

### 7.1 问题一：Serializable 缺失

**报错现象**：首次运行测试时报 `java.io.NotSerializableException`。

**原因分析**：`Ballot` 和 `PaxosLogEntry` 作为 `P1b`、`P2a` 等消息的字段，需要通过网络序列化传输。框架中 `Message` 接口继承了 `Serializable`，但嵌套的自定义类需要额外实现该接口。

**修复方案**：

```java
// 修复前
@Data
final class Ballot implements Comparable<Ballot> { ... }
@Data
final class PaxosLogEntry { ... }

// 修复后
@Data
final class Ballot implements Comparable<Ballot>, Serializable { ... }
@Data
final class PaxosLogEntry implements Serializable { ... }
```

### 7.2 问题二：UNRELIABLE 网络下多客户端测试超时（Test 14, 15）

**报错现象**：在不可靠网络模式下（约 20% 消息丢失），`Multiple clients, synchronous put/get` 和 `Multiple clients, concurrent appends` 测试超时。客户端在规定时间内无法收到响应。

**原因分析**：
- 当 P2a 消息丢失后，Leader 永远无法为该 slot 达到多数派共识，命令永远不会被 chosen
- 当 P1a 消息丢失后，发起选举的服务器无法获得足够的 P1b 响应，永远无法成为 Leader
- 客户端虽然会重试广播请求，但如果 Leader 已经将命令放入日志的某个 slot，重复请求会被 `proposeCommand()` 的去重逻辑跳过，不会重新发起 Phase 2

**修复方案**：添加两层重试机制

1. **Phase 2 重试**（`resendPendingPhase2`）：Leader 在每次心跳（25ms）时重发所有未 chosen 的 P2a 给未响应的 Acceptor

2. **Phase 1 重试**（`resendPhase1`）：在 HeartbeatCheckTimer 中检测到 Phase 1 正在进行但未完成时，重发 P1a 给未响应的服务器

```java
// HeartbeatCheckTimer 处理逻辑
if (!isLeader) {
    missedHeartbeats++;
    if (missedHeartbeats >= 2) {
        startPhase1();  // 完全重新开始
    } else if (phase1Responses.size() > 0 && phase1Responses.size() < majority()) {
        resendPhase1();  // 仅重发未响应的 P1a
    }
}
```

### 7.3 问题三：lastNonEmpty() 返回值错误（Test 17）

**报错现象**：
```
State violates "(Active log slots consistent) ∧ (First non-cleared and last non-empty valid)"
Error info: server4 has status CLEARED for slot 11779 but the lastNonEmpty slot is 0
```

**原因分析**：`lastNonEmpty()` 方法初始值设为 0，只遍历了 `log` HashMap 中的 key。但垃圾回收后，已清除的 slot 的条目已从 `log` 中移除。根据 `PaxosLogSlotStatus` 的定义：
- `EMPTY`：该 slot 没有任何信息
- `CLEARED`：该 slot 的信息已被垃圾回收清除

`CLEARED` ≠ `EMPTY`。如果 `status(11779)` 返回 `CLEARED`，说明 slot 11779 曾经有数据但被清除了，它不是"空"的。`lastNonEmpty()` 应该返回一个 >= 11779 的值。

**修复方案**：

```java
// 修复前
public int lastNonEmpty() {
    int maxSlot = 0;  // BUG：忽略了已清除的 slot
    for (int slot : log.keySet()) { if (slot > maxSlot) maxSlot = slot; }
    return maxSlot;
}

// 修复后
public int lastNonEmpty() {
    int maxSlot = slotCleared;  // 已清除的 slot 也是 non-empty
    for (int slot : log.keySet()) { if (slot > maxSlot) maxSlot = slot; }
    return maxSlot;
}
```

### 7.4 问题四：单服务器 Paxos 超时（Test 27）

**报错现象**：单服务器 Paxos 组（`servers.length == 1`）运行时测试超时（40 秒限制）。

**原因分析**：原始实现中 `startPhase1()` 通过调用 `handleP1a()` 处理自己的 P1a，而 `handleP1a()` 会调用 `send()` 将 P1b 发送到网络队列。在单服务器场景下：
1. `handleP1a()` 将 P1b 放入网络队列
2. P1b 需要等待框架调度才能被投递和处理
3. 处理后才能调用 `handleP1b()` 成为 Leader
4. 此时客户端请求可能已经到达但因 `isLeader == false` 被暂存

这个延迟在多轮操作中会不断累积，最终导致测试超时。

**修复方案**：在 `startPhase1()` 中直接构造本地 P1b 并调用 `handleP1b()`，完全绕过网络层：

```java
// 修复前
for (Address server : servers) {
    if (server.equals(address())) {
        handleP1a(p1a, address());  // 走 send() 网络队列
    } else {
        send(p1a, server);
    }
}

// 修复后
// 1. 直接构造本地 P1b
Map<Integer, PaxosLogEntry> accepted = new HashMap<>();
for (Map.Entry<Integer, PaxosLogEntry> entry : log.entrySet()) {
    if (entry.getKey() > slotCleared) accepted.put(entry.getKey(), entry.getValue());
}
P1b selfP1b = new P1b(address(), myBallot, true, accepted);
handleP1b(selfP1b, address());  // 直接调用，不走网络

// 2. 只向其他服务器发送 P1a
for (Address server : servers) {
    if (!server.equals(address())) send(p1a, server);
}
```

修复后，单服务器场景下 `handleP1b()` 在 `startPhase1()` 内部同步调用，立即看到 `phase1Responses.size() == 1 >= majority() == 1`，直接调用 `becomeLeader()`。整个流程在一次方法调用内完成，无需等待网络调度。


---

## 8. 测试结果

```
TEST  1: Client throws InterruptedException [RUN]                          PASS  (0.67s)   5pts
TEST  2: Single client, simple operations [RUN]                            PASS  (0.11s)   5pts
TEST  3: Progress with no partition [RUN]                                  PASS  (0.09s)   5pts
TEST  4: Progress in majority [RUN]                                        PASS  (0.08s)   5pts
TEST  5: No progress in minority [RUN]                                     PASS  (2.08s)   5pts
TEST  6: Progress after partition healed [RUN]                             PASS  (2.18s)   5pts
TEST  7: One server switches partitions [RUN]                              PASS  (0.18s)  10pts
TEST  8: Multiple clients, synchronous put/get [RUN]                       PASS  (0.29s)  10pts
TEST  9: Multiple clients, concurrent appends [RUN]                        PASS  (0.11s)  10pts
TEST 10: Message count [RUN]                                               PASS  (0.25s)  10pts
TEST 11: Old commands garbage collected [RUN]                              PASS  (4.70s)  15pts
TEST 12: Single client, simple operations [UNRELIABLE]                     PASS  (0.38s)  10pts
TEST 13: Two sequential clients [UNRELIABLE]                               PASS  (0.22s)  10pts
TEST 14: Multiple clients, synchronous put/get [UNRELIABLE]                PASS  (6.45s)  15pts
TEST 15: Multiple clients, concurrent appends [UNRELIABLE]                 PASS  (0.75s)  15pts
TEST 16: Multiple clients, single partition and heal [RUN]                 PASS (11.10s)  15pts
TEST 17: Constant repartitioning, check maximum wait time [RUN]            PASS (30.10s)  20pts
TEST 18: Constant repartitioning, check maximum wait time [UNRELIABLE]     PASS (30.10s)  30pts
TEST 19: Constant repartitioning, full throughput [UNRELIABLE]             PASS (50.30s)  30pts
TEST 20: Single client, simple operations [SEARCH]                         PASS (61.50s)  20pts
TEST 21: Single client, no progress in minority [SEARCH]                   PASS (30.10s)  15pts
TEST 22: Two clients, sequential appends visible [SEARCH]                  PASS (96.80s)  30pts
TEST 23: Two clients, five servers, multiple leader changes [SEARCH]       PASS (55.80s)  20pts
TEST 24: Handling of logs with holes [SEARCH]                              PASS (23.20s)   0pts
TEST 25: Three server random search [SEARCH]                               PASS (20.80s)  20pts
TEST 26: Five server random search [SEARCH]                                PASS (20.50s)  20pts
TEST 27: Paxos runs in singleton group [RUN] [SEARCH]                     PASS  (6.70s)   0pts

Tests passed: 27/27
Points: 355/355 (100.00%)
Total time: ~425s
```

**测试类别说明**：
- **[RUN]**：运行时测试，在实际网络环境中验证功能正确性和性能
- **[UNRELIABLE]**：在 20% 消息丢失率的网络环境下测试
- **[SEARCH]**：模型检查（BFS/DFS 搜索状态空间），穷举验证安全性不变量


---

## 9. 关键设计决策与总结

### 9.1 设计决策

1. **角色合并**：将 PMMC 中 Proposer/Acceptor/Learner/Replica 四种角色合并到 `PaxosServer` 中。好处是简化了消息传递（如本地直接处理 P1b），且每个 slot 只需存储一个 `PaxosLogEntry`（因为 Acceptor 同时知道 accepted 和 chosen 状态）

2. **客户端广播策略**：客户端将请求广播到所有服务器而非单个 Leader。虽然增加了消息数量，但避免了客户端追踪 Leader 身份的复杂性，在 Leader 切换时不会出现请求丢失

3. **心跳驱动重试**：将 P2a 重试集成到心跳机制中（每 25ms 一次），而非引入额外的定时器。简化了设计，同时提供了足够的重试频率

4. **日志去重**：在 `proposeCommand()` 中检查命令是否已在日志中，避免同一命令占用多个 slot，减少了状态空间大小，有利于搜索测试通过

5. **本地 P1b 直接处理**：`startPhase1()` 中对自己的 P1b 不走网络而直接调用 `handleP1b()`，对单服务器场景至关重要，也减少了多服务器场景下的选举延迟

### 9.2 总结

本实验完整实现了基于 Multi-instance Paxos 的容错键值存储系统，共编写 1075 行 Java 代码。通过迭代调试，依次解决了序列化接口缺失、不可靠网络下缺乏消息重试、日志状态查询不一致、单服务器效率低下等四个问题。最终在所有 27 个测试（包括可靠/不可靠网络、网络分区、模型检查搜索）中全部通过，获得 355/355 满分。
