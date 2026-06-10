CREATE TABLE IF NOT EXISTS user (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS storage_location (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    drawer_number INTEGER NOT NULL,
    led_channel INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS item (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'available',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS item_placement (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id INTEGER NOT NULL,
    storage_location_id INTEGER NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    last_checked_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (item_id) REFERENCES item(id),
    FOREIGN KEY (storage_location_id) REFERENCES storage_location(id),
    UNIQUE(item_id, storage_location_id)
);

CREATE TABLE IF NOT EXISTS recognition_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER,
    image_path TEXT NOT NULL,
    llm_model TEXT,
    raw_response TEXT,
    status TEXT NOT NULL DEFAULT 'detected',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES user(id),
    CHECK (status IN ('uploaded', 'detected', 'corrected', 'stored', 'cancelled', 'failed'))
);

CREATE TABLE IF NOT EXISTS recognized_item (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    recognition_session_id INTEGER NOT NULL,
    sequence_no INTEGER NOT NULL,
    item_id INTEGER,
    item_name TEXT NOT NULL,
    confidence REAL,
    status TEXT NOT NULL DEFAULT 'detected',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (recognition_session_id) REFERENCES recognition_session(id),
    FOREIGN KEY (item_id) REFERENCES item(id),
    UNIQUE(recognition_session_id, sequence_no),
    CHECK (sequence_no > 0),
    CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CHECK (status IN ('detected', 'corrected', 'ignored', 'stored'))
);

CREATE TABLE IF NOT EXISTS storage_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    recognition_session_id INTEGER NOT NULL,
    current_sequence_no INTEGER,
    status TEXT NOT NULL DEFAULT 'in_progress',
    started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TEXT,
    FOREIGN KEY (recognition_session_id) REFERENCES recognition_session(id),
    CHECK (current_sequence_no IS NULL OR current_sequence_no > 0),
    CHECK (status IN ('in_progress', 'completed', 'cancelled', 'failed'))
);

CREATE TABLE IF NOT EXISTS storage_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    storage_session_id INTEGER NOT NULL,
    recognized_item_id INTEGER,
    storage_location_id INTEGER,
    drawer_number INTEGER,
    sensor_type TEXT NOT NULL,
    event_type TEXT NOT NULL,
    weight_before REAL,
    weight_after REAL,
    weight_delta REAL,
    payload TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (storage_session_id) REFERENCES storage_session(id),
    FOREIGN KEY (recognized_item_id) REFERENCES recognized_item(id),
    FOREIGN KEY (storage_location_id) REFERENCES storage_location(id),
    CHECK (sensor_type IN ('reed_switch', 'load_cell', 'top_camera', 'system')),
    CHECK (event_type IN (
        'drawer_open',
        'drawer_close',
        'weight_before',
        'weight_after',
        'weight_changed',
        'camera_snapshot',
        'sequence_started',
        'sequence_completed',
        'verification_failed'
    ))
);

CREATE TABLE IF NOT EXISTS drawer_camera_snapshot (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    storage_session_id INTEGER NOT NULL,
    recognized_item_id INTEGER,
    image_path TEXT NOT NULL,
    drawer_number INTEGER,
    note TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (storage_session_id) REFERENCES storage_session(id),
    FOREIGN KEY (recognized_item_id) REFERENCES recognized_item(id)
);

CREATE TABLE IF NOT EXISTS placement_verification (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    storage_session_id INTEGER NOT NULL,
    recognized_item_id INTEGER NOT NULL,
    item_id INTEGER NOT NULL,
    storage_location_id INTEGER NOT NULL,
    open_event_id INTEGER,
    weight_event_id INTEGER,
    close_event_id INTEGER,
    camera_snapshot_id INTEGER,
    result TEXT NOT NULL DEFAULT 'success',
    weight_delta REAL,
    note TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (storage_session_id) REFERENCES storage_session(id),
    FOREIGN KEY (recognized_item_id) REFERENCES recognized_item(id),
    FOREIGN KEY (item_id) REFERENCES item(id),
    FOREIGN KEY (storage_location_id) REFERENCES storage_location(id),
    FOREIGN KEY (open_event_id) REFERENCES storage_event(id),
    FOREIGN KEY (weight_event_id) REFERENCES storage_event(id),
    FOREIGN KEY (close_event_id) REFERENCES storage_event(id),
    FOREIGN KEY (camera_snapshot_id) REFERENCES drawer_camera_snapshot(id),
    UNIQUE(storage_session_id, recognized_item_id),
    CHECK (result IN ('success', 'failed', 'manual_confirmed'))
);

CREATE TABLE IF NOT EXISTS shopping_list (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'pending',
    user_id INTEGER,
    note TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES user(id)
);

CREATE TABLE IF NOT EXISTS shopping_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'open',
    created_by_user_id INTEGER,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TEXT,
    FOREIGN KEY (created_by_user_id) REFERENCES user(id),
    CHECK (status IN ('open', 'compared', 'shopping', 'completed', 'cancelled'))
);

CREATE TABLE IF NOT EXISTS shopping_request (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    note TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES shopping_session(id),
    FOREIGN KEY (user_id) REFERENCES user(id)
);

CREATE TABLE IF NOT EXISTS shopping_request_item (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id INTEGER NOT NULL,
    item_id INTEGER,
    item_name TEXT NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    note TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (request_id) REFERENCES shopping_request(id),
    FOREIGN KEY (item_id) REFERENCES item(id),
    CHECK (quantity > 0)
);

CREATE TABLE IF NOT EXISTS shopping_result_item (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    item_id INTEGER,
    item_name TEXT NOT NULL,
    requested_quantity INTEGER NOT NULL DEFAULT 1,
    owned_quantity INTEGER NOT NULL DEFAULT 0,
    need_to_buy_quantity INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'need_to_buy',
    storage_location_id INTEGER,
    led_channel INTEGER,
    note TEXT,
    purchased_at TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES shopping_session(id),
    FOREIGN KEY (item_id) REFERENCES item(id),
    FOREIGN KEY (storage_location_id) REFERENCES storage_location(id),
    UNIQUE (session_id, item_name),
    CHECK (requested_quantity >= 0),
    CHECK (owned_quantity >= 0),
    CHECK (need_to_buy_quantity >= 0),
    CHECK (status IN ('owned', 'partial', 'need_to_buy', 'purchased', 'skipped'))
);

CREATE TABLE IF NOT EXISTS final_shopping_list (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    title TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ready',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TEXT,
    completed_at TEXT,
    FOREIGN KEY (session_id) REFERENCES shopping_session(id),
    CHECK (status IN ('ready', 'sent_to_store', 'shopping', 'completed', 'cancelled'))
);

CREATE TABLE IF NOT EXISTS final_shopping_list_item (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    final_list_id INTEGER NOT NULL,
    shopping_result_item_id INTEGER,
    item_id INTEGER,
    item_name TEXT NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'pending',
    note TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    purchased_at TEXT,
    FOREIGN KEY (final_list_id) REFERENCES final_shopping_list(id),
    FOREIGN KEY (shopping_result_item_id) REFERENCES shopping_result_item(id),
    FOREIGN KEY (item_id) REFERENCES item(id),
    UNIQUE(final_list_id, item_name),
    CHECK (quantity > 0),
    CHECK (status IN ('pending', 'found_in_store', 'out_of_stock', 'purchased', 'skipped'))
);

CREATE TABLE IF NOT EXISTS store (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    store_type TEXT,
    address TEXT,
    temi_device_id TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS store_section (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    store_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    aisle TEXT,
    shelf TEXT,
    floor INTEGER,
    map_x REAL,
    map_y REAL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (store_id) REFERENCES store(id),
    UNIQUE(store_id, name)
);

CREATE TABLE IF NOT EXISTS store_product (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    store_id INTEGER NOT NULL,
    store_section_id INTEGER,
    item_id INTEGER,
    item_name TEXT NOT NULL,
    in_stock INTEGER NOT NULL DEFAULT 1,
    stock_quantity INTEGER,
    price INTEGER,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (store_id) REFERENCES store(id),
    FOREIGN KEY (store_section_id) REFERENCES store_section(id),
    FOREIGN KEY (item_id) REFERENCES item(id),
    UNIQUE(store_id, item_name)
);

CREATE TABLE IF NOT EXISTS shopping_list_transfer (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    final_list_id INTEGER NOT NULL,
    store_id INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'sent',
    payload TEXT,
    sent_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    received_at TEXT,
    FOREIGN KEY (final_list_id) REFERENCES final_shopping_list(id),
    FOREIGN KEY (store_id) REFERENCES store(id),
    CHECK (status IN ('sent', 'received', 'failed'))
);

CREATE TABLE IF NOT EXISTS store_navigation_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    transfer_id INTEGER NOT NULL,
    store_id INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'in_progress',
    started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TEXT,
    FOREIGN KEY (transfer_id) REFERENCES shopping_list_transfer(id),
    FOREIGN KEY (store_id) REFERENCES store(id),
    CHECK (status IN ('in_progress', 'completed', 'cancelled'))
);

CREATE TABLE IF NOT EXISTS store_navigation_step (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    navigation_session_id INTEGER NOT NULL,
    step_order INTEGER NOT NULL,
    final_list_item_id INTEGER,
    store_product_id INTEGER,
    store_section_id INTEGER,
    instruction TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TEXT,
    FOREIGN KEY (navigation_session_id) REFERENCES store_navigation_session(id),
    FOREIGN KEY (final_list_item_id) REFERENCES final_shopping_list_item(id),
    FOREIGN KEY (store_product_id) REFERENCES store_product(id),
    FOREIGN KEY (store_section_id) REFERENCES store_section(id),
    UNIQUE(navigation_session_id, step_order),
    CHECK (step_order > 0),
    CHECK (status IN ('pending', 'arrived', 'picked', 'skipped', 'out_of_stock'))
);

CREATE INDEX IF NOT EXISTS idx_shopping_request_session_id
    ON shopping_request(session_id);

CREATE INDEX IF NOT EXISTS idx_shopping_request_user_id
    ON shopping_request(user_id);

CREATE INDEX IF NOT EXISTS idx_shopping_request_item_request_id
    ON shopping_request_item(request_id);

CREATE INDEX IF NOT EXISTS idx_shopping_request_item_name
    ON shopping_request_item(item_name);

CREATE INDEX IF NOT EXISTS idx_shopping_result_item_session_id
    ON shopping_result_item(session_id);

CREATE INDEX IF NOT EXISTS idx_shopping_result_item_status
    ON shopping_result_item(status);

CREATE INDEX IF NOT EXISTS idx_final_shopping_list_session_id
    ON final_shopping_list(session_id);

CREATE INDEX IF NOT EXISTS idx_final_shopping_list_item_list_id
    ON final_shopping_list_item(final_list_id);

CREATE INDEX IF NOT EXISTS idx_store_product_store_id
    ON store_product(store_id);

CREATE INDEX IF NOT EXISTS idx_store_product_item_name
    ON store_product(item_name);

CREATE INDEX IF NOT EXISTS idx_shopping_list_transfer_final_list_id
    ON shopping_list_transfer(final_list_id);

CREATE INDEX IF NOT EXISTS idx_store_navigation_session_transfer_id
    ON store_navigation_session(transfer_id);

CREATE INDEX IF NOT EXISTS idx_store_navigation_step_session_id
    ON store_navigation_step(navigation_session_id);

CREATE INDEX IF NOT EXISTS idx_recognized_item_session_id
    ON recognized_item(recognition_session_id);

CREATE INDEX IF NOT EXISTS idx_storage_session_recognition_session_id
    ON storage_session(recognition_session_id);

CREATE INDEX IF NOT EXISTS idx_storage_event_session_id
    ON storage_event(storage_session_id);

CREATE INDEX IF NOT EXISTS idx_storage_event_drawer_number
    ON storage_event(drawer_number);

CREATE INDEX IF NOT EXISTS idx_storage_event_created_at
    ON storage_event(created_at);

CREATE INDEX IF NOT EXISTS idx_drawer_camera_snapshot_session_id
    ON drawer_camera_snapshot(storage_session_id);

CREATE INDEX IF NOT EXISTS idx_placement_verification_session_id
    ON placement_verification(storage_session_id);

CREATE INDEX IF NOT EXISTS idx_placement_verification_item_id
    ON placement_verification(item_id);
