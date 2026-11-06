# 验证记录

## 环境

| 项目 | 值 |
|---|---|
| OS | macOS 26.2 (arm64) |
| JDK | OpenJDK 17.0.21 (Homebrew) |
| Gradle | 8.14.1（wrapper） |
| AGP / Kotlin | 8.10.1 / 2.1.10 |
| Android SDK | platform 34 + build-tools 34.0.0 |
| 模拟器 | Android Emulator 37.3.6.0，Android 14 (API 34) google_apis arm64-v8a，1080×2340 |
| 产物 | `release/TangemL2Recovery.apk`（R8 裁剪的 release 构建，debug keystore 签名，5.89 MiB） |

> 本机 Google Maven CDN 会间歇性返回 404，构建走 `build-env/` 里的本地镜像重试代理。
> 该代理只影响构建，不进入 App。

## 1. 编译与测试

```
./gradlew clean assembleRelease  -> BUILD SUCCESSFUL
./gradlew testDebugUnitTest      -> 37 tests, 0 failures
./gradlew lintDebug              -> 0 errors, 25 warnings（版本/风格建议）
```

### 构建类型与体积（R8）

| 构建 | APK | dex 类型数 | 冷启动（模拟器） |
|---|---|---|---|
| v1（重构前） | 18.9 MiB | — | — |
| v2 `assembleDebug`（未裁剪，仅开发用） | 31.88 MiB | 54,735 | +3720 ms |
| v2 `assembleRelease`（R8 + 资源裁剪，交付用） | **5.89 MiB** | 10,294 | **+399 ~ +666 ms** |

debug 包里占比最大的两类是 web3j 传递依赖（RxJava / BouncyCastle / SpongyCastle /
Jackson / jnr，24.2% 的 dex 类型）和 `material-icons-extended`（20.9%，项目只用了 15 个图标
却带进 11,429 个图标类）。R8 会摇掉这些未使用部分。`app/proguard-rules.pro` 对 Tangem
NFC 栈、web3j crypto API、应用自身和 zxing 采用保守 keep —— 钱包应用宁可多留一些类，
也不能在贴卡签名时因反射缺类而失败。

R8 裁剪包已在 Android 14 模拟器上重新安装并冒烟验证：无崩溃、Tangem SDK 初始化正常、
UI 渲染一致（`docs/screenshots/11-r8-release.png`）。

### 单元测试覆盖（37 项）
| 测试类 | 数量 | 覆盖内容 |
|---|---|---|
| `TxCodecTest` | 5 | EIP-155 规范参考签名哈希；与 web3j 参考签名器**逐字节对拍**；Scroll 534352 大 chainId（`v` 超 1 字节）且发送方可由节点正确恢复；公钥不匹配必须拒绝；`hashHex` 一致性 |
| `UnitsTest` | 7 | 18 位精度无损解析/格式化；超精度输入必须抛错；负数/空/逗号/科学计数法必须拒绝；round-trip |
| `ValidatorsTest` | 6 | EIP-55 官方测试向量；校验和判定；地址/calldata 校验；粘贴内容归一化；地址缩写 |
| `QrPayloadParserTest` | 12 | EIP-681（带函数 / 带 data / 纯地址）；JSON（含 0x chainId）；裸 calldata；裸地址；URL 参数；WalletConnect 拒绝；垃圾输入不抛异常 |
| `CalldataDecoderTest` | 7 | ERC-20 选择器；transfer 编码字布局；解码出参数；未知选择器标记为盲签；畸形输入不抛异常；`bytes32` 与动态 `string` 代币符号 |

## 2. 产物静态校验（aapt2）

```
package name='com.example.tangeml2' versionCode='2' versionName='2.0'
sdkVersion:'24'  targetSdkVersion:'34'
launchable-activity: com.example.tangeml2.MainActivity
uses-permission: NFC / INTERNET / ACCESS_NETWORK_STATE / CAMERA
application: networkSecurityConfig, dataExtractionRules, allowBackup=false 均已生效
zip 完整性: OK
```

合并清单确认 `com.journeyapps.barcodescanner.CaptureActivity` 已注册（扫码可用）。

## 3. 模拟器运行验证

安装并启动，**无崩溃、无 ANR**：

```
adb install -r release/TangemL2Recovery.apk   -> Success
adb shell am start -n com.example.tangeml2/.MainActivity
logcat: [APP/ui] MainActivity onCreate
        [SDK/Info] initNfcManager
        [APP/card] Tangem SDK 初始化完成（离线认证）
        [APP/vm] 已加载 5 个网络，当前 Mode
        ActivityTaskManager: Displayed ... +3s720ms
adb logcat -b crash -> 空
```

### 界面截图（`docs/screenshots/`）

| 截图 | 验证内容 |
|---|---|
| `01-light-main.png` | 浅色主界面：标题栏 + 网络胶囊 + 状态横幅 + 余额卡 + 扫码签署卡 + 步骤 ①②③ + 固定底部操作栏 |
| `02-dark-mode.png` | 深色主界面：所有文字/背景对比度正常（v1 的硬编码浅灰在此已不存在） |
| `03-contract-mode.png` | 切换到「合约调用」：分段控件状态、琥珀色盲签警告、calldata 输入框 |
| `04-network-sheet.png` | 深色网络面板：5 条链、chainId + RPC 等宽显示、当前链高亮打勾 |
| `05-qr-review.png` | **扫码结果面板**：来源徽章、函数徽章、链不一致警告、目标合约、chainId、金额、gas、`transfer` 解码结果与参数表、原始 calldata、双操作按钮 |
| `06-confirm-sheet.png` | **交易确认面板**：网络/从/到/资产/数量/手续费上限/合计/调用、calldata、自定义网络警告、取消 / 贴卡签名 |
| `07-log-sheet.png` | 诊断日志面板：真实运行日志、刷新/清空/导出/关闭 |
| `08-clean-start-dark.png` | 移除临时 QA 注入后重新构建的干净启动态（确认不会自动弹出面板） |
| `09-light-network-sheet.png` | 浅色网络面板 |
| `10-add-network-dialog.png` | 添加自定义网络对话框：三个字段、占位符、空值时确认按钮正确禁用 |
| `11-r8-release.png` | **R8 裁剪后的 release 包**启动结果：UI 与 debug 包一致、无崩溃 |

### 交互验证
- 分段控件切换（原生币 / ERC20 / 合约调用）正常，联动显示不同字段。
- 底部弹窗（网络选择 / 扫码结果 / 交易确认 / 日志）均可正常展开、下滑关闭、返回键关闭。
- 状态横幅随操作变化 tone（信息蓝 → 成功绿 → 警告琥珀 → 错误红）。
- 地址与 calldata 长文本按 `maxLines` + 省略号换行，未撑破卡片。

### 关于截图中的「扫码结果」与「交易确认」
这两个面板需要真实扫到二维码 / 真实卡片才能触发。为了完成视觉验证，临时在
`RecoveryViewModel` 中注入了一份演示状态（EIP-681 `transfer` 样例）构建了 QA 包并截图，
**随后已完整还原并重新构建**（`grep TEMP-QA` 为 0 处，最终 APK 已重新安装并确认启动后
不再自动弹出面板）。截图内容因此是真实渲染结果，但触发路径是临时注入。

## 4. 代码走查（针对无法自动验证的运行期行为）

- `MainActivity` 继承 `FragmentActivity` —— 已从 Tangem SDK 字节码确认
  `NfcSessionDialog` 依赖 `FragmentActivity`（`TangemSdk.init(FragmentActivity, Config)`），
  用 `ComponentActivity` 会导致 NFC 会话无法展示。
- Tangem 派生只在**同一个 NFC 会话**内完成（`Config.defaultDerivationPaths`），
  不发起第二次会话，避免 `50003 Busy`。
- zxing `CaptureManager` 自带 CAMERA 运行时权限申请，无需额外代码。
- 网络：`networkSecurityConfig` 禁止明文，仅放行 loopback；添加网络时对非本机
  `http://` 直接给出明确错误，而不是让请求在运行期莫名失败。
- 日志导出改为 `suspend` + `Dispatchers.IO`，不在主线程等待写盘。
- 所有可滚动容器都有明确高度约束，不存在「无限高度」崩溃组合。

## 5. 仍需真机验证的部分

模拟器没有 NFC 硬件，因此以下路径只能靠代码走查，**必须在真机上过一遍**：

1. 贴卡读卡 → 派生地址（`scanCard` / `derive`）。
2. 签名流程（`sign` → recovery id 恢复 → EIP-155 组装 → 广播）。签名组装本身已由
   单元测试用官方向量验证，但 NFC 会话交互只能真机测。
3. 真实链上的余额查询与广播（模拟器可联网，但没有可用的测试卡）。
4. **R8 裁剪后的 NFC 签名路径**：模拟器只验证了启动、Tangem SDK 初始化和 UI 渲染。
   `app/proguard-rules.pro` 已保守 keep 整个 `com.tangem.**` 与 `org.web3j.**`，
   但贴卡时才触发的反射路径仍建议真机确认一次。若遇到 `ClassNotFoundException`，
   在 `docs/` 里附日志并把对应类加入 keep 规则即可。

建议：
```bash
adb install -r release/TangemL2Recovery.apk
# 先用 Sepolia 测试网 + 极小额跑通全流程
```
