# p9-os-domain6 · 文件系统（P9 深度实验）

> OS 轨道域 6/9。覆盖：VFS/inode/文件系统类型/日志(journal)/COW/崩溃一致性
> 对照 13 本书：现代操作系统 Ch9-13

---

## 实验 1：文件系统类型（df/mount 实测）

```
/dev/sda2    ext4     147G  99G  41G  71%  /           ← 根分区 ext4
tmpfs        tmpfs    1.6G  2.9M              /run      ← 内存文件系统
tmpfs        tmpfs    7.8G                    /dev/shm  ← 共享内存
overlay      overlay                            /var/lib/docker/...  ← Docker overlay
/dev/sr0     iso9660  6.0G                    /media/... ← ISO 镜像
```

### 常见文件系统

| 类型 | 特点 | 用途 |
|---|---|---|
| **ext4** | 日志/成熟/稳定 | Linux 默认（通用） |
| **xfs** | 高性能/大文件 | 数据库/大文件 |
| **btrfs** | COW/快照/压缩 | 快照/压缩需求 |
| **tmpfs** | 内存文件系统 | /tmp//dev/shm（快但掉电丢） |
| **overlay** | 分层（lower+upper） | Docker 容器镜像 |
| **nfs/cifs** | 网络文件系统 | 共享存储 |

### P9 追问链

- **Q：ext4 和 xfs 区别？** → ext4 成熟通用；xfs 高性能大文件（数据库常用）
- **Q：tmpfs 是什么？** → 内存文件系统（快，掉电丢；/tmp//dev/shm 用）
- **Q：overlay 用在哪？** → Docker 镜像分层（lowerdir 只读+upperdir 可写）

---

## 实验 2：inode（实测）

```
磁盘使用: 99G / 147G (71%)
inode 使用: 1113096 / 9830400 (12%)
Block size: 4096
Inodes Total: 9830400 / Free: 8717304
```

### inode 机制

```
inode：文件元数据（不含文件名）
  内容：文件大小/权限/所有者/时间戳/数据块指针
  文件名存在目录项（dirent）→ 指向 inode 号

 inode 耗尽问题：
  大量小文件 → inode 用尽（磁盘还有空间但无法创建文件）
  → df -i 查看 inode 使用率
  → 修复：清理小文件/调整 inode 数量（mkfs 时配置）
```

### P9 追问链

- **Q：inode 存什么？** → 元数据（大小/权限/时间戳/数据块指针），不含文件名
- **Q：文件名存在哪？** → 目录项（dirent）→ 指向 inode 号
- **Q：inode 耗尽怎么办？** → df -i 查看；清理小文件（磁盘有空间但 inode 满）

---

## 补充：VFS（虚拟文件系统）

```
VFS：内核文件系统抽象层
  → 统一接口（open/read/write/close）
  → 支持多种文件系统（ext4/xfs/tmpfs/nfs...）
  → 每种文件系统实现 VFS 接口（如 ext4_file_operations）

调用链：
  应用 read() → VFS → ext4_file_operations->read → ext4 驱动 → 磁盘
```

### P9 追问链

- **Q：VFS 是什么？** → 内核文件系统抽象层（统一接口，支持多种 FS）
- **Q：read() 怎么到磁盘？** → VFS → 文件系统驱动（ext4）→ 磁盘

---

## 补充：日志（Journal，崩溃一致性）

```
问题：写文件时崩溃 → 文件系统不一致（数据块写了一半）
  → fsck 修复（慢，可能丢数据）

日志（Journal）：
  写数据前先写日志（intent）
  → 崩溃后重放日志（恢复一致性）
  → ext4 日志模式：
     journal：数据+元数据都写日志（最安全，最慢）
     ordered：只元数据写日志+数据先落盘（默认，平衡）
     writeback：只元数据写日志（最快，可能丢数据）
```

### P9 追问链

- **Q：ext4 日志模式？** → journal（全写）/ordered（元数据+数据先落盘，默认）/writeback（只元数据）
- **Q：日志有什么代价？** → 写放大（先写日志再写数据）；ordered 模式默认（平衡安全性能）

---

## 补充：COW（Copy-On-Write）文件系统

```
COW 文件系统（btrfs/ZFS）：
  写入时复制数据块（不原地覆盖）
  → 快照：记录 COW 元数据（几乎零成本）
  → 崩溃一致性：写新块+原子更新元数据（不会半写）
  → 代价：碎片化（频繁写入）

vs ext4 日志：
  ext4：原地覆盖+日志（写日志→写数据）
  btrfs：COW（写新块→更新指针）（更安全，支持快照）
```

### P9 追问链

- **Q：COW 文件系统好处？** → 快照零成本/崩溃一致性（不原地覆盖）
- **Q：COW 有什么代价？** → 碎片化（频繁写入产生碎片）

---

## 补充：崩溃一致性

```
崩溃一致性问题：
  写文件时崩溃 → 数据块写一半 → 文件系统不一致

解决方案：
  日志（ext4）：先写日志再写数据，崩溃后重放
  COW（btrfs）：写新块+原子更新指针（不原地覆盖）
  fsck：崩溃后扫描修复（慢，可能丢数据）

生产建议：
  数据库：用支持 COW 或强日志的 FS（btrfs/ext4 ordered）
  避免 ext4 writeback（可能丢数据）
```

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **文件系统类型** | ext4（通用）/xfs（高性能）/btrfs（COW快照）/tmpfs（内存）/overlay（Docker） | 实验 1（df/mount 实测） |
| **inode** | 元数据（不含文件名）；inode 耗尽=大量小文件 | 实验 2（df -i 实测） |
| **VFS** | 内核文件系统抽象层（统一接口，支持多种 FS） | 补充（叙事） |
| **日志模式** | ext4：journal/ordered（默认）/writeback | 补充（叙事） |
| **COW 文件系统** | btrfs/ZFS 写新块+原子更新（快照零成本） | 补充（叙事） |
| **崩溃一致性** | 日志（重放）/COW（不原地覆盖）/fsck（修复） | 补充（叙事） |
| overlay | Docker 分层（lowerdir 只读+upperdir 可写） | 实验 1（mount 实测） |

## 相关文件

- df/mount 实测：ext4/overlay/tmpfs/inode 统计
- 概念：VFS/日志/COW/崩溃一致性叙事
