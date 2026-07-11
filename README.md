# Tangem L2 Recovery

**English** · [中文](#中文)

A minimal Android companion wallet for **Tangem NFC hardware cards**, built to
sign transactions on **L2 networks the official Tangem app does not support**
(e.g. **Mode**) and any custom EVM chain.

## Why this exists

The official Tangem app only supports the networks baked into its closed-source
`blockchain-sdk`. If you bridge or receive tokens on a network it doesn't list
(such as **Mode**, chainId 34443), those assets become **invisible and
unmovable** in the official app — there's no way to add a custom RPC/chainId.

This tool exists so you can **still sign and move those stranded tokens**, and
as a safety net **before** you accidentally send tokens to a network the
official app can't reach. Add any chain by RPC + chainId, scan your card, sign.

## Features

- Add **custom networks** (name + RPC; chainId auto-fetched & verified on-chain)
- Send **native ETH**, **ERC20 tokens**, or **arbitrary contract calls** (paste calldata — covers redeem / bridge / approve)
- **QR scan** for recipient address · **MAX** button (exact balance, no float rounding)
- Pre-send **balance check** (blocks `value + gas > balance` before you tap the card)
- **On-chain receipt verification** after broadcast (real success vs revert — no fake "sent")
- Correct L2 **gas pricing** (uses network price, no inflated floor) + `estimateGas`
- Persistent **on-device log** with export button (for diagnosing failed transfers)

## Build

```bash
# JDK 17 + Android SDK (compileSdk 34). Dependencies vendored in offline-repo/.
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
# prebuilt: release/TangemL2Recovery.apk
```

## Optional: bind to your own address

`EXPECTED_ADDRESS` in `app/src/main/java/com/example/tangeml2/L2Network.kt` is
**empty by default** (generic tool, no address bound). Set it to your own
address if you want the app to verify the card's derived key matches a known
address on scan.

## ⚠️ Security

- Your Tangem card's **access code (PIN)** is enforced by the Tangem SDK itself — this app adds no custom crypto.
- **"Contract call" is blind signing** — the app cannot decode calldata. Only paste calldata from sources you trust.
- **Always test with a tiny amount first.** L2s (especially zkSync-stack chains like zkLink Nova) have gas quirks.
- Debug-signed APK. Review the source before signing anything real.

---

## 中文

一个极简的 **Tangem NFC 硬件钱包** 安卓伴侣应用,专为在 **Tangem 官方应用不支持的 L2
网络**(如 **Mode**)及任意自定义 EVM 链上签名交易而做。

### 为什么需要它

官方 Tangem 应用只支持其闭源 `blockchain-sdk` 内置的网络。如果你把代币跨链/接收到了它
未收录的网络(比如 **Mode**,chainId 34443),这些资产在官方应用里会**看不到、也动不了**——
官方应用**不支持添加自定义 RPC / chainId**。

本工具的用途:让你**仍能签名、把这些被困的代币转出来**,同时作为一道**防线**——
避免有人不小心把代币转入官方应用够不到的网络后无法取回。用 RPC + chainId 添加任意链,
贴卡,签名即可。

### 功能

- 添加**自定义网络**(名称 + RPC;chainId 自动从链上拉取并校验)
- 发送**原生 ETH**、**ERC20 代币**、或**任意合约调用**(粘贴 calldata——覆盖 赎回 / 跨链桥 / approve)
- 收款地址**二维码扫描** · **全部余额**按钮(用整数精确填入,杜绝浮点上浮)
- 发送前**余额校验**(`金额 + gas > 余额` 时直接拦下,不浪费贴卡)
- 广播后**回链核实 receipt**(区分真成功与 revert,不再假报"已发送")
- 正确的 L2 **gas 定价**(用网络实价,无虚高地板)+ `estimateGas`
- 设备内**持久化日志** + 导出按钮(用于诊断失败的转账)

### 编译

```bash
# JDK 17 + Android SDK (compileSdk 34)。依赖已内置在 offline-repo/。
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
# 预编译: release/TangemL2Recovery.apk
```

### 可选:绑定你自己的地址

`app/src/main/java/com/example/tangeml2/L2Network.kt` 里的 `EXPECTED_ADDRESS`
**默认为空**(通用工具,不绑定任何地址)。如需让应用在扫卡时校验派生地址是否与已知地址
一致,填入你自己的地址即可。

### ⚠️ 安全须知

- 卡片的**访问密码(PIN)**由 Tangem SDK 原生强制,本应用**不添加任何自定义加解密**。
- **"合约调用"是盲签**——应用无法解析 calldata,只粘贴你信任来源的 calldata。
- **务必先用极小额测试。** L2(尤其 zkLink Nova 等 zkSync 栈链)的 gas 行为有差异。
- Debug 签名 APK。动真钱前请先审阅源码。
