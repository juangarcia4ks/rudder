// SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::thread;

use rudder_relayd::{configuration::cli::CliConfiguration, init_logger, start};

mod common;

#[test]
fn it_correctly_replies_to_info_api() {
    let cli_cfg = CliConfiguration::new("tests/files/config/", false);
    thread::spawn(move || {
        start(cli_cfg, init_logger().unwrap()).unwrap();
    });
    assert!(common::start_api().is_ok());

    let response: serde_json::Value = serde_json::from_str(
        &reqwest::blocking::get("http://localhost:3030/rudder/relay-api/1/system/info")
            .unwrap()
            .text()
            .unwrap(),
    )
    .unwrap();

    let reference: serde_json::Value = serde_json::from_str("{\"data\":{\"major-version\":\"0.0\",\"full-version\":\"0.0.0-dev\"},\"result\":\"success\",\"action\":\"getSystemInfo\"}").unwrap();

    assert_eq!(reference, response);
}
