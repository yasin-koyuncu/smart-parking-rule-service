-- Run after test-schema.sql: takes parking_spots back to what it was before parking-mapping-service added camera views,
-- the database of a deployment that has not applied that migration yet.
drop table if exists camera_views;
alter table parking_spots drop column if exists view_id;
alter table parking_spots drop column if exists image_polygon;
alter table parking_spots alter column coordinates set not null;
