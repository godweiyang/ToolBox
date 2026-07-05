## 修复：WiFi 热力图拖动时整个屏幕被跟着拖

### 问题
- 在热力图页拖动地图时，左右滑动会被 ViewPager2 拦截，导致切到「实时信号」Tab
- 双指缩放时也可能触发同样问题

### 修复
- 在 HeatMapView.onTouchEvent 中，当检测到双指缩放或已放大后的单指拖动时，调用 `parent.requestDisallowInterceptTouchEvent(true)` 阻止父级（ViewPager2）拦截触摸事件
- 手指抬起后恢复 `requestDisallowInterceptTouchEvent(false)`，此时左右滑动可正常切 Tab
- 未放大时（userScale ≈ 1）单指滑动仍允许 ViewPager2 切 Tab，避免影响正常翻页
