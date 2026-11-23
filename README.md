# 炒鸡美化 · ChaojiBeauty

面向 ColorOS 的 LSPosed 美化模块，提供状态栏重绘、通知叠放、锁屏与桌面下拉通知中心，以及桌面容器玻璃效果。

## 0.4.129（157）公开重建版

这一版根据保留的项目备份和历史修改记录重建。沿用包名 `cn.zhe.chaojibeauty`、版本号 `0.4.129` 和版本代码 `157`。数字时钟、日期和电量数字使用 Android 系统字体。清除、收起与充电符号由项目代码绘制。

- 通知卡片在各自轮廓内实时采样背景，卡内柔焦半径为 14dp。
- 锁屏展开与桌面下拉共用顶部渐隐，通知可滚过状态栏背后。
- 锁屏单条通知只显示主卡，两条默认只露一层圆角底边。

## 下载与安装

- [下载公开重建 APK](https://github.com/ggchen175-lgtm/ColorOS-/raw/main/downloads/ChaojiBeauty-0.4.129-rebuild.apk)
- [SHA-256 校验文件](downloads/SHA256SUMS)

安装需要 Root 与可用的 LSPosed 环境。状态栏和通知功能使用「系统界面」作用域，桌面材质使用「系统桌面」作用域，负一屏材质使用「负一屏」作用域。更新后重启相关进程。左侧下拉通知中心需要 ColorOS 的通知栏与控制中心分离布局。

公开包使用 Android 调试签名。覆盖安装时，Android 要求已安装包和下载包签名一致，版本代码也需满足系统的升级规则。

## 验证和构建

历史测试包在一加 15（PLK110，ColorOS 17）上验证了通知叠放、深浅色下拉滚动与不同显示密度。**本次公开重建包通过了 `assembleDebug` 与 `lintDebug`，真机验证仍待进行。** 公开重建包有独立的校验值。

使用 JDK 17、Gradle 8.7 和 Android SDK 34，从仓库根目录运行：

```sh
gradle :app:assembleDebug :app:lintDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

代码按 [GPL-3.0](LICENSE) 发布。Dock 部分光学实现采用 [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 的 Apache 2.0 代码，署名及许可证见[第三方声明](app/src/main/assets/notices/AndroidLiquidGlass.txt)。
