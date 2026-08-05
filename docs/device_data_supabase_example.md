# device_data Supabase 扩展示例

这个文档整理自用户提供的 Supabase 建表教程，目的是给项目后续扩展留一个干净参考。

注意：

- 当前 App 默认上传的仍然是 `fsr_sessions` 和 `fsr_minute_data`。
- 本文档里的 `device_data` 只是可选扩展表，不会被当前版本自动写入。
- 如果你后面想把手机侧更多上下文数据同步到 Supabase，可以按这里的结构继续扩展。

## 适用场景

`device_data` 适合存储更通用的设备侧数据，例如：

- 前台应用包名
- 位置坐标和地址
- 应用使用时长统计
- 通知列表
- 设备事件
- 健康数据

这类数据和当前 FSR 会话摘要不是一回事，所以建议单独建表，不要混进 `fsr_sessions`。

## 建表示例

```sql
create table if not exists public.device_data (
  id bigserial primary key,
  timestamp text,
  foreground_app text,
  location_latitude double precision,
  location_longitude double precision,
  location_address text,
  location_city text,
  location_district text,
  location_street text,
  app_usage jsonb,
  notifications jsonb,
  device_event text,
  health_data jsonb,
  created_at timestamptz not null default now()
);
```

## 注释示例

```sql
comment on table public.device_data is '设备实时数据同步表';
comment on column public.device_data.timestamp is '数据产生时间';
comment on column public.device_data.foreground_app is '前台应用包名';
comment on column public.device_data.device_event is '设备事件：screen_on / screen_off / boot';
comment on column public.device_data.health_data is '健康数据 JSON：心率、步数、睡眠、血氧等';
```

## 索引建议

```sql
create index if not exists idx_device_data_timestamp
on public.device_data (timestamp desc);

create index if not exists idx_device_data_created_at
on public.device_data (created_at desc);

create index if not exists idx_device_data_event
on public.device_data (device_event)
where device_event is not null;
```

## RLS 示例

下面这套策略只适合个人实验或局域网环境：

```sql
alter table public.device_data enable row level security;

create policy "device_data_anon_read"
on public.device_data
for select
to anon
using (true);

create policy "device_data_anon_update"
on public.device_data
for update
to anon
using (true)
with check (true);

create policy "device_data_anon_delete"
on public.device_data
for delete
to anon
using (true);
```

如果项目会给别人用，建议改成更严格的鉴权，不要直接暴露宽松的匿名写权限。

## 自动清理示例

如果只是短期缓存，可以用 `pg_cron` 保留最近 3 天：

```sql
create extension if not exists pg_cron;

select cron.schedule(
  'retain_device_data_3_days',
  '0 3 * * *',
  $$ delete from public.device_data where created_at < now() - interval '3 days'; $$
);
```

## 和当前项目的关系

- `fsr_sessions`：存触摸会话摘要，适合 AI 问“昨晚发生了什么”。
- `fsr_minute_data`：存分钟聚合，适合低 token 查看趋势。
- `device_data`：适合扩展保存其他手机侧设备上下文，不属于当前默认上传链路。
