import requests
import json
import time


def test_chat_api():
    url = "http://127.0.0.1:8000/api/v1/chat"

    # 准备测试数据
    payload = {
        "prompt": "当前宏声桥站拥挤度0.85，外面正在下暴雨，乘客较多，应该怎么进行公交调度建议？",
        "max_tokens": 512,
        "temperature": 0.3
    }

    headers = {
        "Content-Type": "application/json"
    }

    print(f"发送请求到: {url}")
    print(f"提问内容: {payload['prompt']}\n" + "-" * 30)

    start_time = time.time()

    try:
        # 发送 POST 请求
        response = requests.post(url, json=payload, headers=headers)
        response.raise_for_status()  # 检查 HTTP 错误

        # 解析返回结果
        result = response.json()
        end_time = time.time()

        if result.get("status") == "success":
            print("🟢 模型回答:\n")
            print(result["data"]["response"])
            print("\n" + "-" * 30)
            print(f"⏱️ 耗时: {end_time - start_time:.2f} 秒")
        else:
            print(f"🔴 服务端返回错误: {result}")

    except requests.exceptions.ConnectionError:
        print("❌ 连接失败：请确认 app.py (微服务) 是否已经启动并在运行中！")
    except Exception as e:
        print(f"❌ 发生未知错误: {e}")


if __name__ == "__main__":
    test_chat_api()