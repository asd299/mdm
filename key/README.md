# 签名密钥与发布说明

本目录存放本项目 Android 安装包的**签名密钥**及其说明。仓库内所有 APK（含 `MisterDownloadManager-Android-v1.5.2.apk`）
均由这里的证书签名，因此覆盖安装时不会出现「签名不一致、请先卸载」的问题。

## 文件

| 文件 | 说明 |
|---|---|
| `mdm-release.keystore` | PKCS12 密钥库，当前唯一签名来源 |
| `sign-apk.sh` | 一键 `zipalign` + `apksigner` 签名脚本 |

## 证书信息

| 项 | 值 |
|---|---|
| 密钥库路径 | `key/mdm-release.keystore` |
| 密钥库类型 | PKCS12 |
| 别名（alias） | `androiddebugkey` |
| 密钥库密码（storepass） | `android` |
| 密钥密码（keypass） | `android` |
| 密钥算法 | RSA 2048 / SHA256withRSA |
| 证书主体 | `C=US, O=Android, CN=Android Debug` |
| 有效期 | 2026-11-22 → 2056-11-14 |
| SHA-1 | `34:32:74:58:9E:E1:0B:5A:7E:22:7B:4A:9C:93:76:F8:EE:C2:61:09` |
| SHA-256 | `44:05:EE:13:56:C4:7A:DF:95:4F:A1:89:BB:BD:62:C0:45:32:8F:66:F7:98:A5:4C:8E:95:0A:70:BC:D1:9F:86` |

## 用法

### 1. 命令行签名

```bash
# 自动找 build-tools，签名并校验
./key/sign-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk \
                  MisterDownloadManager-Android-v1.5.2.apk

# 自定义密码 / 别名 / build-tools
KS_PASS=android KEY_PASS=android KEY_ALIAS=androiddebugkey \
BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0 \
./key/sign-apk.sh <unsigned.apk> <out.apk>
```

### 2. Gradle 中配置（可选）

在 `app/build.gradle.kts` 的 `android { }` 内加入：

```kotlin
signingConfigs {
    create("release") {
        storeFile = rootProject.file("key/mdm-release.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
    }
}
```

### 3. 校验安装包签名

```bash
apksigner verify --print-certs MisterDownloadManager-Android-v1.5.2.apk
# 输出的 SHA-256 应与上表一致
```

## 重要说明与风险

1. **这是 Android 调试证书**（`CN=Android Debug`），用默认密码 `android` 导出。它的唯一目的是让各个版本的
   安装包保持同一签名、可以互相覆盖升级，**不适合上架应用商店**。
2. 仓库当前为**私有仓库**。若将来改为公开，请务必先移除本目录并重新生成正式密钥：证书与密码一旦公开，
   任何人都能签发被系统视为「同一应用」的安装包。
3. 正式发布请新建密钥（`keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000`），
   使用强密码，并且**不要**提交到版本库；同时注意换证书后旧版本无法直接覆盖，需要卸载重装。
4. 密钥库一旦丢失，将无法再为已安装的用户提供可覆盖升级的安装包，请自行备份。

> 若你只是自用或小范围分发，保持现状即可：签名稳定、可覆盖安装，且与仓库内所有历史 APK 一致。
