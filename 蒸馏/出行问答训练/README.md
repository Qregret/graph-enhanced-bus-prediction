# 出行问答训练数据

这套数据用于训练系统回答用户的公交出行问题，输出结合客流、拥挤度、道路拥堵、天气、景区扰动和当前位置的出行建议。

## 当前输出文件

- `travel_advice_dataset.csv`
  - 基础合成问答样本底表
- `travel_advice_dataset_glm.csv`
  - GLM 标注后的完整结果
- `travel_advice_train_glm.jsonl`
  - 训练集，默认 400 条
- `travel_advice_val_glm.jsonl`
  - 验证集，默认 100 条
- `generate_travel_advice_dataset.py`
  - 生成与打标脚本

## 数据来源

底层线路与站点来自：

- `蒸馏/database/bus_distillation_dataset.csv`

其中：

- `line_id / line_name / station_id / station_name`
  来自真实线路和站点底表
- `current_location / destination_station / user_question`
  为合理场景合成
- `advice_level / reasoning / assistant_answer`
  由 GLM 生成

## 修改条数

脚本顶部可直接调整：

- `DEFAULT_MAX_SAMPLES`
- `DEFAULT_TRAIN_SIZE`
- `DEFAULT_VAL_SIZE`

## 运行方式

```powershell
python ".\\蒸馏\\出行问答训练\\generate_travel_advice_dataset.py"
```
