fn main() {
    unsafe {
    // 1. Tell Rust where the 'protoc' compiler is (using the vendored crate)
    std::env::set_var("PROTOC", protoc_bin_vendored::protoc_bin_path().unwrap());
}
    // 2. Compile the proto file. Use the path relative to Cargo.toml.
    prost_build::compile_protos(
        &["../proto/sync_event.proto"], 
        &["../proto/"]
    ).unwrap();
}