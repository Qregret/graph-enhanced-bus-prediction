CREATE INDEX idx_dwd_od_line_dir_dt
ON dwd_od (line_id, is_up_down, dt);

CREATE INDEX idx_ods_route_station_route_dir_serial
ON ods_route_station_full (route_id, direction, serial_number);

CREATE INDEX idx_ods_station_inc_id
ON ods_station_inc (id);
