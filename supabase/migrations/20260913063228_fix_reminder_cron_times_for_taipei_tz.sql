
select cron.alter_job(1, schedule := '0 1 * * *');   -- morning-wood: 09:00 Asia/Taipei (UTC+8)
select cron.alter_job(2, schedule := '0 13 * * *');  -- arousal: 21:00 Asia/Taipei
select cron.alter_job(3, schedule := '30 13 * * *'); -- stool: 21:30 Asia/Taipei
