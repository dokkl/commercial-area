CREATE TABLE IF NOT EXISTS store (
  store_id      VARCHAR(24)   NOT NULL,
  store_name    VARCHAR(255)  NOT NULL,
  branch_name   VARCHAR(255),
  large_code    VARCHAR(10)   NOT NULL,
  large_name    VARCHAR(100)  NOT NULL,
  medium_code   VARCHAR(10)   NOT NULL,
  medium_name   VARCHAR(100)  NOT NULL,
  small_code    VARCHAR(10)   NOT NULL,
  small_name    VARCHAR(100)  NOT NULL,
  sido_code     VARCHAR(10)   NOT NULL,
  sido_name     VARCHAR(50)   NOT NULL,
  sgg_code      VARCHAR(10)   NOT NULL,
  sgg_name      VARCHAR(50)   NOT NULL,
  dong_code     VARCHAR(20),
  dong_name     VARCHAR(50),
  lot_address   VARCHAR(255),
  building_name VARCHAR(255),
  road_address  VARCHAR(255),
  floor_info    VARCHAR(30),
  lon           DECIMAL(10,7) NOT NULL,
  lat           DECIMAL(10,7) NOT NULL,
  PRIMARY KEY (store_id),
  KEY idx_geo       (lat, lon),
  KEY idx_sgg_geo   (sgg_code,   lat, lon),
  KEY idx_dong_geo  (dong_code,  lat, lon),
  KEY idx_large_geo (large_code, lat, lon),
  KEY idx_small_geo (small_code, lat, lon),
  KEY idx_name      (store_name(20))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS region (
  sido_code   VARCHAR(10)   NOT NULL,
  sido_name   VARCHAR(50)   NOT NULL,
  sgg_code    VARCHAR(10)   NOT NULL,
  sgg_name    VARCHAR(50)   NOT NULL,
  dong_code   VARCHAR(20)   NOT NULL,
  dong_name   VARCHAR(50)   NOT NULL,
  store_count INT           NOT NULL,
  min_lat     DECIMAL(10,7) NOT NULL,
  max_lat     DECIMAL(10,7) NOT NULL,
  min_lon     DECIMAL(10,7) NOT NULL,
  max_lon     DECIMAL(10,7) NOT NULL,
  PRIMARY KEY (sido_code, sgg_code, dong_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS industry (
  large_code  VARCHAR(10)  NOT NULL,
  large_name  VARCHAR(100) NOT NULL,
  medium_code VARCHAR(10)  NOT NULL,
  medium_name VARCHAR(100) NOT NULL,
  small_code  VARCHAR(10)  NOT NULL,
  small_name  VARCHAR(100) NOT NULL,
  store_count INT          NOT NULL,
  PRIMARY KEY (large_code, medium_code, small_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
