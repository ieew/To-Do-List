-- 设备级凭证：让无法持有账号密码的宿主（输入法插件）自主续期短票据。
-- 只保存 sha256，不保存明文；每次续期都轮换。

ALTER TABLE dsh_devices ADD COLUMN credential_hash TEXT;

ALTER TABLE dsh_devices ADD COLUMN credential_expires_at INTEGER;

ALTER TABLE dsh_devices ADD COLUMN credential_rotated_at INTEGER;
