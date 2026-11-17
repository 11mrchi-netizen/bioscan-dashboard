ALTER TABLE supplements ADD COLUMN every_n_days INTEGER NULL CHECK (every_n_days IS NULL OR every_n_days >= 2);
