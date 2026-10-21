# 实现说明

## 自动切换

MainActivity 编辑本地 Config；MonitorService 监听音频设备与模式变化，合并事件后请求 Shizuku UserService。

EffectUserService 检查调用 UID，仅允许所属 App 请求固定操作，通过 app_process 运行 NativeProbe。子进程建立真实 shell UID 对应的 com.android.shell Context 和归因，查询实际媒体输出，再应用设备规则。

NativeProbe 管理杜比、小米音效、沉浸声与 EQ。当前固件的系统 EQ 操作涉及 Dolby 和 MiSound 两套后端，写入和读回结果分开报告；不把不可读回当成验证成功。通话、设备变化或非目标路由会暂缓。

## 主动页面同步

MiSound 图形均衡器从其私有偏好读取曲线，底层音效参数改变不会自动更新页面。App 的主动按钮先打开系统 Activity，再用临时 UiAutomation 获取十段控件边界、逐段触摸并检查结果。

页面操作在独立进程中完成；没有常驻无障碍服务。进入页面后等待窗口、布局和曲线稳定；每次操作检查输出路由与解锁状态，遇到变化停止。±6 dB 范围按系统 0.5 dB 量化，边缘档位有专门处理。

结束或失败后，后台服务重新读取当前设备，恢复已保存的底层方案。页面同步有固定超时；它只由用户点击触发，不参与熄屏事件。

## 源码入口

| 文件 | 用途 |
| --- | --- |
| src/dev/mi14/audiofxauto/MainActivity.java | 设备规则、预设及设置界面 |
| src/dev/mi14/audiofxauto/MonitorService.java | 事件调度、前台服务、任务串行 |
| src/dev/mi14/audiofxauto/EffectUserService.java | Shizuku 子进程管理 |
| src/dev/mi14/audiofxauto/ConfigStore.java | 本地设备和方案保存 |
| src/mi14/audiofx/NativeProbe.java | 路由检查、系统音效调用 |
| src/mi14/audiofx/StockEq.java | 两套 EQ 后端协调 |
| src/mi14/audiofx/eqpage/PageSyncRunner.java | 主动系统页面同步 |
| validation/ | 离线测试与发布检查 |

系统接口依赖具体固件，不以取消检查来扩展适配。新增机型应独立验证权限、归因、参数、路由和生命周期。
