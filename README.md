# Tangem L2 Recovery — 可安装 App + 完整可编译工程

用你的 Tangem NFC 硬件卡，把卡在 Mode / Scroll / zkLink Nova 等 L2 上的 ETH 和 ERC20 代币转出来。硬件签名，私钥永不离卡。

---

## 📦 交付物

```
/Users/zosie/Downloads/tangem/
├── TangemL2Recovery.apk           ← 直接装手机（17MB，已签名，含全部修复）
├── TangemL2Recovery-source.zip    ← 完整可编译工程（自包含，换机即编）
└── TangemL2Recovery/              ← 同上，未压缩的工程目录
```

---

## 📱 安装

**方式 A（数据线，最稳）** — 手机开 USB 调试后：
```bash
~/android-toolchain/android-sdk/platform-tools/adb install -r "/Users/zosie/Downloads/tangem/TangemL2Recovery.apk"
```
**方式 B** — 把 apk 发到手机点开安装，允许「未知来源」。

> Debug 签名测试包，功能完整，无法上架商店。手机提示「未验证开发者」选「仍然安装」。

---

## 🕹️ 使用流程

1. **① 选择网络** — 下拉选 Mode / Scroll / zkLink Nova / Sepolia 测试网，或点「➕ 添加自定义网络」输入名称+RPC（chainId 自动从 RPC 拉取校验，防止签错链）。
2. **② 扫描 Tangem 卡片** — 贴卡到 NFC 区。App 派生地址并自动与主网地址 `0xafE1…7421` 比对（✅通过 / ⚠️不匹配会警告）。下方显示卡片固件和是否设了密码。
3. **③ 资产类型**：
   - **原生 ETH** — 直接转 ETH。
   - **ERC20 代币** — 输入代币合约地址 → 点「🔍 查询代币信息」确认符号/精度/余额 → 再发送。
4. **④ 收款信息** — 手输地址，或「📷 扫码填入」扫二维码，或「🧪 填入本卡地址」自转测试。填金额。
5. **⑤ 签名并发送** — 再次贴卡，卡内 secp256k1 签名 → 广播 → 显示交易哈希和浏览器链接。

---

## 🔧 换机重新编译

工程**自包含**（内置 `offline-repo/` 离线 Tangem SDK），无需联网拉 Tangem SDK：
```bash
# 解压后
cd TangemL2Recovery
echo "sdk.dir=/你的/Android/SDK/路径" > local.properties   # 唯一需要改的
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```
> 已用**全新空 gradle 缓存**冷编译验证通过（7分21秒，36 任务全绿），证明工程真正可移植。
> 首次编译需联网下载 web3j / androidx / zxing 等公共依赖（Tangem SDK 走内置离线仓库）。

### 工程结构
```
TangemL2Recovery/
├── offline-repo/          # ★ 内置 Tangem SDK 3.9.2（AAR+JAR+POM），换机编译关键
├── settings.gradle.kts    # 仓库配置（offline-repo 优先）
├── gradle/libs.versions.toml
├── app/
│   ├── build.gradle.kts   # 依赖：Tangem SDK + Web3j 4.9.8 + zxing
│   └── src/main/
│       ├── AndroidManifest.xml   # NFC + CAMERA 权限
│       ├── java/com/example/tangeml2/
│       │   ├── MainActivity.kt        # 全流程 UI
│       │   ├── TransactionHelper.kt   # 交易构建/EIP-155签名恢复/ERC20/广播
│       │   └── L2Network.kt           # 网络定义（预置+自定义）
│       └── res/
```

---

## 🔐 关于「原版 Tangem 要输密码」

排查结论：**本 App 没有任何自定义加解密逻辑会阻断转账**。
- 若你的卡设过 access code（密码），签名时是 **Tangem SDK 原生弹出**的密码框，输入即继续——这是 SDK 行为，不是 App 加的障碍。
- 若没设密码，直接贴卡签名。
- 扫码后的界面会显示「已设密码 / 无密码」供你确认。

---

## ⚠️ 动真钱前务必做

**先在 Sepolia 测试网演练一遍**（已内置在网络列表）：领测试 ETH → 扫卡确认地址匹配 → 发一笔小额自转 → 浏览器确认成功。签名恢复（recovery id / EIP-155 v）是唯一的丢币点，字节码正确 ≠ 链上一定接受，只能真机+真卡端到端验证一次才稳。

**zkLink Nova 是 zkSync 栈链**，Legacy 交易是否被完全接受属经验问题，务必先在该链小额自测。

---

## 📋 已知边界（非 bug，是设计范围）

| 支持 | 不支持 |
|------|--------|
| 原生 ETH 转账 | EIP-1559 交易（L2 基本兼容 Legacy） |
| ERC20 `transfer()` 转账 | ERC20 `approve` + `transferFrom` 授权转账 |
| 自定义网络/合约地址 | 任意 dApp 合约调用 / WalletConnect |
| 二维码扫**收款地址** | 二维码扫任意合约 ABI |

> ERC20 的 `transfer()` 花的是你自己的代币余额，**不需要** approve。
> 二维码仅用于填收款地址，不是签署任意合约的入口。
