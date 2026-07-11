# Tangem L2 Recovery

**English** · [中文](README.zh-CN.md)

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
# prebuilt: release/TangemL2Recovery.apk  (or the Releases page)
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
