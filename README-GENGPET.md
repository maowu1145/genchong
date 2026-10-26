# 梗宠 Android 核心体验版 0.1.2

原生 Java 17 / Android 8.0+，应用 ID `com.maowu.gengpet`。与旧应用使用独立包名，不覆盖其安装和数据。没有登录、云聊天或网络权限。

## 0.1.2 界面改版

角色馆改为双列图鉴，召唤按钮直接露出；已召唤伙伴集中为头像栏。工具页使用六个入口，设置页分组；调节面板从底部打开并明确默认设置/单只设置的作用对象。统一 Material Symbols Rounded 本地图标、圆角与蓝紫色控件；大字体自动单列。实机检查见 `docs/UI改版验收-0.1.2.txt`，图标许可见 `docs/licenses/`。

## 0.1.1 手机修复

已接入 boxob 的24节点软体和集中绘制方式，降低窗口更新开销；修复透明边缘悬空、拖动误回收、临时窗口泄漏及召唤时恢复整组的问题。默认偏硬、重心下移，点击角色可调柔软度。新召唤与恢复上一组分开。实际设备检查及尚未覆盖范围见 `docs/手机修复验收-0.1.1.txt`。

## 当前可用范围

- 桌宠馆：角色图鉴、按需拖拽试玩、搜索、收藏、角色详情、按实例设置大小/柔软度/重力/固定/静音。
- 集中绘制层与每只独立触控区：最多六只，可重复角色，拖拽抛掷、共同物理碰撞与弹性形变、回收区、快捷菜单、暂停/恢复。
- Doro：独立喝水和水果提醒、时间与安静区间、稍后/已知晓/今天不再、主动开启后台通知。
- 熊猫：SQLite 文字记录与搜索，本机 AAC/M4A 录音、保存、播放、重命名、删除、系统分享和中断草稿恢复。录音仅在可见工具页面进行，离开页面会停止并保存可用录音。
- 旋转猫：用户确认的 8 帧 256px / 40ms 快速旋转；可关闭的实验性乘车边缘提示，使用传感器滤波和低动态模式。
- 黄豆：八种心情，每只独立保存，最多十二字文字。
- 摇摇车：已确认保留的 8 帧摇摆；普通话常见亲属关系链计算，未知条件保留候选。
- 全家桶小猫：本机真实日期/时区的周四状态与每日短时庆祝、安静控制。
- 设置：低功耗、安静时段、权限入口、ZIP 导出文字/录音/设置、清空本地数据。

目前是六个核心角色、45 张运行图片。策划 B—F 批其余 30 款仍在待制作清单，不在本次 APK 中，不计作已完成。这个版本不是完整 36 角色策划的最终验收。

## 构建

需要 JDK 17、Android SDK 平台 36 和 Build Tools 36.0.0。工程自带 Gradle 8.11.1 Wrapper，使用 AGP 8.10.1。`minSdk=26 / targetSdk=36`。

1. 将 SDK 路径配置到本地 `local.properties` 的 `sdk.dir`（不提交该文件），或设置 `ANDROID_HOME`。
2. 将 `JAVA_HOME` 设置到 JDK 17。
3. 执行 `./gradlew.bat :app:assembleDebug :app:lintDebug`。
4. 安装包为 `app/build/outputs/apk/debug/app-debug.apk`，使用本机 Android 调试签名，供直接安装测试；正式发布需要独立发布签名与素材使用范围确认。

本次 Windows 环境中，JDK 的 Unix-domain socket 连接失败。可用 `./build-gengpet.ps1 -JavaHome <JDK路径> -SdkRoot <SDK路径> -TcpPipeFallback` 重现本次构建。脚本只在当前进程使用 OpenJDK 的 TCP 回退，不改系统设置；正常机器可省略此开关。

## 验证

- `coordination/tests/run-physics-tests.ps1`：独立物理测试。
- `tools-tests/run.ps1`：关系链、提醒边界与本地日期规则。
- `python coordination/assets/validate_assets.py --source-root <素材包解压目录>`：资源完整性、帧序、Alpha、来源哈希与边界。本机可省略参数。
- 真实执行结果和未完成的设备检查见 `docs/验收记录.txt`。

测试脚本默认检测本次电脑的 JDK 路径；移到其他电脑时将 JDK 17 的 `javac`、`java` 放入 PATH，或调整脚本的候选路径。

## 工程结构

- `app/src/main/java/com/maowu/gravitypet/gengpet/`：本次应用全部运行源码。
- `app/src/gengpet/res/`：本版主题和应用图标，旧版资源不参与构建。
- `app/src/main/assets/pets/catalog.json`：六角色目录；角色 PNG 与帧动画同目录保存。
- `coordination/assets/source-to-runtime.json`：45 张图的源文件、哈希、裁切与锚点溯源。
- `coordination/assets/pending-30.*`：尚未制作的候选角色。
- `coordination/*-report.txt`：功能、物理、资源实现与边界说明。
- 旧项目源码保留用于对照；Gradle 仅编译 gengpet 子目录，旧云聊天和旧 Activity 不进入本版应用。

复用基线 `YunshaoTech/xiaobiga@9f2e32eeb916dd5e27f4bc42f2355944fe648e4c` 的重力、摩擦、碰撞冲量和弹簧思路；具体映射见 overlay-report。未推送远程仓库。

## 使用说明

首次打开可点“试玩”拖动 Doro。选择“召唤”后，在系统页面允许悬浮窗，再返回应用。点桌宠打开菜单，长按打开专属工具，拖入回收区收回。通知和麦克风按使用时申请；拒绝后可继续使用其他功能。暂停保留位置，关闭保留已选角色，“收回全部”清空当前伙伴。

导出文件包含可读 JSON 与原始录音；本版不包含一键导入恢复入口。已分享出去的文件无法由本应用撤回。传感器提示是乘客可选实验功能，不承诺治疗或防止晕车。