-- Profile details the user can edit: the name RoboFriend calls them and their birthday.
ALTER TABLE users ADD COLUMN display_name VARCHAR(50);
ALTER TABLE users ADD COLUMN birthday DATE;
