// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

pub use resource::Policy;

mod native;
pub mod resource;

// we probably need a CFEngine ${} parser
// it would be good to be able to warn on undefined variables
// so getting the list of known CFEngine system vars would be good
