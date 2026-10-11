#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AutoGLM-Phone 真机测试脚本（mini Agent 循环）
原理: 截图 -> 发给 autoglm-phone 云端模型 -> 解析动作 -> ADB 执行 -> 循环

用法:
  python autoglm_test.py --apikey "你的key" "打开设置"
  python autoglm_test.py --apikey "你的key" --steps 5 "在哔哩哔哩搜索英雄联盟"

前置条件:
  1. 手机 USB 调试已连接 (adb devices 可见)
  2. 安装了 ADB Keyboard 并启用（文本输入需要）
     下载: https://github.com/senzhk/ADBKeyBoard/blob/master/ADBKeyboard.apk
  3. 智谱 API key: bigmodel.cn 注册 -> 控制台 -> API Keys

也可以把 key 存到环境变量 ZHIPU_API_KEY 或同目录 apikey.txt
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

ADB = r"C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"

# 常见 App 中文名 -> 包名映射（Launch 动作用）
APP_MAP = {
    "微信": "com.tencent.mm", "抖音": "com.ss.android.ugc.aweme",
    "淘宝": "com.taobao.taobao", "京东": "com.jingdong.app.mall",
    "拼多多": "com.xunmeng.pinduoduo", "支付宝": "com.eg.android.AlipayGphone",
    "美团": "com.sankuai.meituan", "饿了么": "me.ele",
    "设置": "com.android.settings", "哔哩哔哩": "tv.danmaku.bili",
    "B站": "tv.danmaku.bili", "小红书": "com.xingin.xhs",
    "微博": "com.sina.weibo", "百度地图": "com.baidu.BaiduMap",
    "高德地图": "com.autonavi.minimap", "QQ": "com.tencent.mobileqq",
    "网易云": "com.netease.cloudmusic", "QQ音乐": "com.tencent.qqmusic",
    "知乎": "com.zhihu.android", "携程": "ctrip.android.view",
    "12306": "com.MobileTicket", "滴滴": "com.sdu.didi.psnger",
}


def adb(*args, binary=False):
    r = subprocess.run([ADB, *args], capture_output=True)
    return r.stdout if binary else r.stdout.decode("utf-8", errors="ignore").strip()


def screenshot_b64():
    png = adb("exec-out", "screencap", "-p", binary=True)
    if not png or len(png) < 1000:
        raise RuntimeError("截图失败，检查 adb 连接")
    return base64.b64encode(png).decode()


def call_model(apikey, task, img_b64, step, history):
    """调用 autoglm-phone，返回 (think, answer) 文本"""
    user_content = [
        {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{img_b64}"}},
        {"type": "text", "text": f"<task>{task}</task>"},
    ]
    messages = [{"role": "system", "content": "You are AutoGLM, a helpful AI assistant for phone task execution."}]
    messages += history
    messages.append({"role": "user", "content": user_content})

    body = json.dumps({
        "model": "autoglm-phone",
        "messages": messages,
        "temperature": 0.6,
        "max_tokens": 2048,
        "stream": False,
    }).encode("utf-8")

    req = urllib.request.Request(API_URL, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {apikey}")

    with urllib.request.urlopen(req, timeout=90) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    content = data["choices"][0]["message"]["content"]
    # AutoGLM 输出格式: <think>...</think><answer>do(action="Tap", coordinate=(x,y))</answer>
    think_m = re.search(r"<think>(.*?)</think>", content, re.S)
    answer_m = re.search(r"<answer>(.*?)</answer>", content, re.S)
    think = think_m.group(1).strip() if think_m else ""
    answer = answer_m.group(1).strip() if answer_m else content.strip()
    return think, answer, content


def parse_action(answer):
    """解析 do(...) 动作，返回 (action, 参数 dict)"""
    m = re.search(r'do\(\s*action\s*=\s*"(\w+)"\s*(.*?)\)', answer, re.S)
    if not m:
        return None, {}
    action = m.group(1)
    rest = m.group(2)
    params = {}
    for k, v in re.findall(r'(\w+)\s*=\s*"?([^",)]+)"?', rest):
        if k != "action":
            params[k] = v.strip().strip("'\"")
    # 坐标特殊处理: coordinate=(x, y)
    coord = re.search(r"coordinate\s*=\s*\((\d+)\s*,\s*(\d+)\)", rest)
    if coord:
        params["x"], params["y"] = int(coord.group(1)), int(coord.group(2))
    return action, params


def execute(action, params, task):
    """把模型动作翻译成 adb 命令"""
    if action == "Launch":
        app = params.get("app", "")
        pkg = app if "." in app else APP_MAP.get(app, "")
        if not pkg:
            # 尝试模糊匹配
            for name, p in APP_MAP.items():
                if name in app or app in name:
                    pkg = p
                    break
        if not pkg:
            print(f"  ⚠️ 无法识别 App: {app}，跳过")
            return
        adb("shell", "monkey", "-p", pkg, "-c",
            "android.intent.category.LAUNCHER", "1")
        print(f"  → 启动 {app} ({pkg})")
        time.sleep(3)
    elif action == "Tap":
        x, y = params.get("x", 0), params.get("y", 0)
        adb("shell", "input", "tap", str(x), str(y))
        print(f"  → 点击 ({x}, {y})")
        time.sleep(2)
    elif action == "Swipe":
        coords = re.findall(r"\d+", json.dumps(params))
        if len(coords) >= 4:
            adb("shell", "input", "swipe", *coords[:4])
            print(f"  → 滑动 {coords[:4]}")
        time.sleep(2)
    elif action == "Type":
        text = params.get("text", "")
        adb("shell", "am", "broadcast", "-a", "ADB_INPUT_TEXT",
            "--es", "msg", text)
        print(f"  → 输入: {text}")
        time.sleep(1.5)
    elif action == "Back":
        adb("shell", "input", "keyevent", "4")
        print("  → 返回")
        time.sleep(1.5)
    elif action == "Home":
        adb("shell", "input", "keyevent", "3")
        print("  → 回桌面")
        time.sleep(1.5)
    elif action in ("Long Press", "LongPress"):
        x, y = params.get("x", 0), params.get("y", 0)
        adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "800")
        print(f"  → 长按 ({x}, {y})")
        time.sleep(1.5)
    elif action in ("Double Tap", "DoubleTap"):
        x, y = params.get("x", 0), params.get("y", 0)
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(0.2)
        adb("shell", "input", "tap", str(x), str(y))
        print(f"  → 双击 ({x}, {y})")
        time.sleep(1.5)
    elif action == "Wait":
        print("  → 等待 3 秒")
        time.sleep(3)
    elif action in ("Take_over", "TakeOver"):
        print("\n  🙋 模型请求人工接管（登录/验证码等）。请手动操作后按回车继续…")
        input()
    elif action in ("Finish", "Stop", "Done", "Complete"):
        print("  ✅ 模型判断任务完成")
        return "DONE"
    else:
        print(f"  ⚠️ 未知动作: {action} {params}")
    return None


def main():
    parser = argparse.ArgumentParser(description="AutoGLM-Phone 真机测试")
    parser.add_argument("task", help="任务指令，如: 打开设置")
    parser.add_argument("--apikey", default=os.environ.get("ZHIPU_APIKEY", ""))
    parser.add_argument("--steps", type=int, default=15, help="最大步数")
    args = parser.parse_args()

    apikey = args.apikey
    if not apikey and os.path.exists("apikey.txt"):
        apikey = open("apikey.txt").read().strip()
    if not apikey:
        print("❌ 缺少 API key。用法: python autoglm_test.py --apikey xxx \"任务\"")
        print("   或: echo 你的key > apikey.txt")
        sys.exit(1)

    # 检查设备
    dev = adb("devices")
    if "device\t" not in dev.replace("device ", "device\t"):
        print(f"❌ 无设备连接:\n{dev}")
        sys.exit(1)
    print(f"📱 设备: {dev.splitlines()[1].split()[0]}")
    print(f"🎯 任务: {args.task}")
    print(f"🔄 开始 Agent 循环 (最大 {args.steps} 步)\n" + "=" * 50)

    history = []
    for step in range(1, args.steps + 1):
        print(f"\n[步骤 {step}] 截图 → 云端理解…")
        img = screenshot_b64()
        think, answer, raw = call_model(apikey, args.task, img, step, history)

        if think:
            print(f"  💭 {think[:150]}{'…' if len(think) > 150 else ''}")
        print(f"  🎯 {answer}")

        # 记录到历史（模型需要上下文）
        history.append({"role": "user", "content": f"<image step={step}>"})
        history.append({"role": "assistant", "content": raw})
        if len(history) > 10:
            history = history[-10:]

        action, params = parse_action(answer)
        if action is None:
            print("  ⚠️ 无法解析动作，原文输出。继续下一步…")
            time.sleep(2)
            continue
        if execute(action, params, args.task) == "DONE":
            print("\n" + "=" * 50)
            print(f"🏁 任务完成，共 {step} 步")
            return
        time.sleep(1.5)

    print("\n⏹ 达到最大步数，结束。")


if __name__ == "__main__":
    main()
