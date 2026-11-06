# v2 重构说明（v1 → v2）

## 一、这次改了什么

| 维度 | v1 | v2 |
|---|---|---|
| 架构 | 单 `Activity` + 622 行「上帝类」，逻辑与视图混在一起 | MVVM：`RecoveryViewModel` + `StateFlow<RecoveryUiState>` + `RecoveryActions` 接口；UI 只渲染状态、只发意图 |
| UI | XML 布局，默认控件 + emoji 标签，硬编码 `#EEEEEE` / `#666666` | Jetpack Compose + Material 3 设计系统，浅/深色双主题，品牌色板、等宽字体展示地址与金额 |
| 分层 | 4 个文件平铺 | `domain` / `data`(rpc·abi·tx·card·network·token·qr) / `core`(util·log) / `ui`(theme·components·screens) |
| 测试 | 无 | 37 个单元测试，覆盖 EIP-155 签名、金额精度、EIP-55、二维码解析、calldata 解码 |
| 二维码 | 仅扫收款地址，用 `Regex("0x[0-9a-fA-F]{40}")` 粗暴提取 | **新增「扫码签署合约」**：EIP-681 / JSON / 裸 calldata / URL / WalletConnect 识别 |

## 二、修复的 Bug

1. **自定义网络重启即丢** —— 原先 `networks` 只是内存里的 `mutableListOf`。现在用
   SharedPreferences 持久化（`data/network/NetworkStore`），并保存上次选中的链。
2. **深色模式下文字看不见** —— 布局里硬编码 `#EEEEEE` 背景 + `@color/black` 文字，
   而主题是 `DayNight`。现在所有颜色来自主题色板，浅/深色各自校验对比度。
3. **日志同步写盘阻塞调用线程** —— 原先 `AppLog.add()` 直接在调用线程 `appendText`，
   而它经常在主线程被调用（ANR 风险）。现在写入统一投递到单线程队列。
4. **日志轮转要把整个 2MB 文件读进内存** —— 现在只保留尾部窗口。
5. **金额超精度被静默截断** —— `BigDecimal(...).toBigInteger()` 会悄悄丢掉多余小数位。
   现在显式校验并抛出中文错误（`Units.parseAmountToRaw`）。
6. **原生币余额写死 ETH / 18 位** —— 现在每个网络带 `nativeSymbol` / `nativeDecimals`。
7. **`estimateGas` 失败时静默兜底 100000** —— 合约调用可能因此 out-of-gas 白烧 gas。
   现在把节点报错抛给调用方并在界面上显示原因。
8. **发送前没有任何确认步骤** —— 误触即动钱。现在有独立的确认面板，逐项列出网络、
   chainId、from、to、金额、手续费上限、合计与 calldata。
9. **状态文字埋在长滚动页底部** —— 出错时用户看不到。现在状态横幅固定贴在顶部。
10. **RPC 无重试** —— 网络抖动直接报错。现在 3 次退避重试，且 5xx/429 才重试、
    协议错误不重试。
11. **回执轮询用 `Thread.sleep` 阻塞 25 秒且无进度** —— 现在 `delay` 可取消，
    并实时显示已等待秒数。
12. **EIP-681 二维码取错地址** —— 原正则抓到的是**代币合约地址**而不是收款人。
    现在按 EIP-681 语法解析 target / chainId / function / params。
13. **快速切换网络时余额响应串台** —— 旧请求的结果会覆盖新网络。现在取消旧 Job
    并在写入前校验网络 id。
14. **`symbol()` 返回 `bytes32` 的老代币显示为 `?`** —— 现在两种 ABI 布局都支持。
15. **崩溃兜底处理器无限递归** —— 原实现里 `setDefaultUncaughtExceptionHandler` 之后
    又调用 `getDefaultUncaughtExceptionHandler()`，拿到的是自己。现在先捕获旧处理器。
16. **代币精度读不到时兜底 18** —— 发送路径保留「必须抛错中止」的强约束，
    并额外限制返回值范围，避免异常精度把金额放大。

## 三、新增能力

- **扫码签署合约**（见 README 表格）＋ **calldata 解码器**（30 个常见方法选择器），
  未知选择器强制显示盲签警告。
- **交易确认面板**与**手续费预估**（输入即估算，防呆）。
- **诊断日志面板**：查看 / 刷新 / 清空 / 导出（FileProvider 分享）。
- **网络管理面板**：内置链 + 自定义链列表，可删除自定义链。
- 地址一键复制、EIP-55 校验和显示。

## 四、验证情况

```
./gradlew testDebugUnitTest    -> 37 tests, 0 failures
./gradlew assembleRelease      -> BUILD SUCCESSFUL, 5.89 MiB（R8 裁剪）
```

关键验证点：`TxCodecTest` 用 **EIP-155 官方测试向量**校验签名哈希，并与 **web3j 自带
签名器逐字节对拍**，另外覆盖 Scroll 大 chainId（`v` 超 1 字节）与「公钥不匹配必须拒绝」。

### 发布包体积修正

初次发布误用了未裁剪的 `assembleDebug` 产物（31.88 MiB）。后续固化为 **`assembleRelease`**：
开启 R8 代码裁剪 + 资源裁剪，交付包降至 **5.89 MiB（-81.5%）**，比 v1 的 18.9 MiB 还小。
debug 包里占比最大的 web3j 传递依赖（24.2% dex 类型）与 `material-icons-extended`
（20.9%，仅用 15 个图标却带进 11,429 类）在 release 构建中被摇掉。
裁剪包已在 Android 14 模拟器重新安装验证：无崩溃、Tangem SDK 初始化正常、UI 一致。
`app/proguard-rules.pro` 对 Tangem NFC 栈刻意保守 keep，避免贴卡签名时因反射缺类而失败。

## 五、已知限制

- 只构造 **Legacy(type-0) EIP-155** 交易。所有目标链都接受，但没有实现 EIP-1559
  的 `maxFeePerGas`；在纯 1559 链上会以 `gasPrice` 形式提交。
- 二维码里的 EIP-681 函数参数若含**动态类型**（`bytes` / `string` / 数组），
  解析器会明确拒绝而不是猜测编码。
- 未实现 WalletConnect 会话；`wc:` 码会被识别并提示改用支持的钱包。
- UI 未做多语言，界面文案为中文。
