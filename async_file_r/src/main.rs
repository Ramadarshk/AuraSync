use std::{ path::Path, result, time::Duration};
use prost::Message;
use notify::{Config, RecommendedWatcher, RecursiveMode, Watcher};
use regex::RegexSet;
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, UdpSocket}, sync::mpsc::Receiver, time::sleep};
use dashmap::DashMap;
pub mod file_sync {
    include!(concat!(env!("OUT_DIR"), "/aurasync.rs"));
}


// struct FileStability{
//     last_Size: u64,
//     last_Modified: Option<std::time::SystemTime>,
//     stable_Count: u8,
// }

lazy_static::lazy_static!{
    static ref IGNORE_PATTERNS: RegexSet = RegexSet::new(&[
        r"^\..*",                // Hidden files
        r".*\.tmp$",             // Temporary files
        r".*~$",                 // Backup files
        r"^Thumbs\.db$",         // Windows thumbnail cache
        r"^Desktop\.ini$",       // Windows desktop settings
        r"^\.DS_Store$",         // macOS directory settings
    ]).unwrap();
    static ref SYNC_REGISTRY: DashMap<String, String> = DashMap::new();
    
}

async fn compute_blake3_hash(path: &Path) -> Result<String, std::io::Error> {
    let mut file = tokio::fs::File::open(path).await?;
    let mut hasher = blake3::Hasher::new();
    let mut buffer = [0; 8192];

    loop {
        let n = file.read(&mut buffer).await?;
        if n == 0 {
            break;
        }
        hasher.update(&buffer[..n]);
    }

    Ok(hasher.finalize().to_hex().to_string())
}


fn is_ignore(path:&Path)->bool {
    let file_name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
    IGNORE_PATTERNS.is_match(file_name)
}


#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let relative_path = "./AuraSync";
    std::fs::create_dir_all(relative_path).unwrap();
    println!("[main-21] engine started: {}", relative_path);

    let sync_root = std::fs::canonicalize(relative_path)?;


    tokio::spawn(async move {
        let socket = UdpSocket::bind("0.0.0.0:8888").await.unwrap();
        socket.set_broadcast(true).unwrap();
        let msg = b"AuraSync Engine Discovery";
        let broadcast_addr = "255.255.255.255:8888";
        println!("[main-33] broadcasting to {}", broadcast_addr);
        loop {
            let _ = socket.send_to(msg, broadcast_addr).await;
            sleep(Duration::from_secs(3)).await;
        }
    });

    let (tx,mut rx) = tokio::sync::mpsc::channel(100);
    let mut watchers = RecommendedWatcher::new( move |res: result::Result<notify::Event, notify::Error>|  {
        if let Ok(event) = res{
         
        println!("[main-44] watcher event: {:?}", event.kind);
        let _ = tx.blocking_send(event);
        }
    },Config::default())?;
    watchers.watch(&sync_root, RecursiveMode::Recursive)?;

    let mut rx = rx;
    let tcp_listener = TcpListener::bind("0.0.0.0:9999").await?;
    println!("[main-52] TcpListener bound to 0.0.0.0:9999");
    loop {
        let (socket, ip_addr) = tcp_listener.accept().await?;
        println!("[main-55] New connection from {}", ip_addr);
        let root_clonized = sync_root.clone();
        handle_connection(socket,&mut rx, root_clonized).await;
    }
}

async fn handle_connection(mut socket: tokio::net::TcpStream, rx: &mut Receiver<notify::Event>,root: std::path::PathBuf) {
    let mut len_buf = [0u8; 4];
    // let 
    loop {
    tokio::select! {
        val = socket.read_exact(&mut len_buf)=>{
           if val.is_err(){ 
               println!("[handle_connection-68] connection closed");
               break;
           } 
           let len = u32::from_be_bytes(len_buf) as usize;
           let mut mes = vec![0u8; len];
           socket.read_exact(&mut mes).await.unwrap();
           let event = file_sync::SyncEvent::decode(&mes[..]).unwrap();
           println!("[handle_connection-75]received event from phone: {:?}", event);
           // Store the hash immediately. This IS the ignore mechanism.
           let normalized_path = event.file_path.trim_start_matches('/').to_string();
            SYNC_REGISTRY.insert(normalized_path, event.checksum.clone());
           handle_phone_event(&event,&mut socket).await;
        }
        Some(event) = rx.recv()=>{
            println!("[handle_connection-87]file system event: {:?}", event);
            if let Some(path) = event.paths.first() {
                println!("[handle_connection-89]processing path: {:?}", path);
                if is_ignore(path) {
                    println!("[handle_connection] Ignoring temporary file: {:?}", path);
                    continue;
                }
                let file_path = path.strip_prefix(&root).unwrap().to_string_lossy().to_string();
                let file_path = file_path.trim_start_matches('/').to_string();
                let current_hash = if(event.kind.is_remove()){
                    SYNC_REGISTRY.remove(&file_path).map(|(_, h)| h).unwrap_or_default()
                } else {
                    match compute_blake3_hash(&path).await {
                        Ok(hash) => hash,
                        Err(e) => {
                            println!("[handle_connection-97]Failed to compute hash for {:?}: {}", path, e);
                            continue;
                        }
                    }
                };
                if let Some(last_hash) = SYNC_REGISTRY.get(&file_path) {
                    if *last_hash == current_hash { 
                        println!("[handle_connection] Echo detected for {}, skipping.", file_path);
                        continue; 
                    }
                }
                let (action, file_size) = if event.kind.is_remove() {
                        (file_sync::sync_event::Action::Delete, 0u64)
                    } else {
                        let metadata = match tokio::fs::metadata(&path).await {
                            Ok(m) => m,
                            Err(e) => {
                                println!("[handle_connection-103]Failed to get metadata for {:?}: {}", path, e);
                                continue;
                            }
                        };
                        let act = if event.kind.is_create() { file_sync::sync_event::Action::Create } 
        
                                 else { file_sync::sync_event::Action::Modify };
                        (act, metadata.len())
                    };
                    if !event.kind.is_remove() {
                        SYNC_REGISTRY.insert(file_path.clone(), current_hash.clone());
                    }
                let sync_event = file_sync::SyncEvent {
                    action: action as i32,
                    file_path: file_path.clone(),
                    new_path: String::new(),
                    file_size: file_size.clone(),
                    checksum: current_hash.clone(),
                    permissions:0,
                };
                let mut buf = Vec::new();
                sync_event.encode(&mut buf).unwrap();
                let len = (buf.len() as u32).to_be_bytes();
                if socket.write_all(&len).await.is_ok() && socket.write_all(&buf).await.is_ok(){
                    println!("[handle_connection-124]sent event to phone: {:?}", sync_event);
                    if action == file_sync::sync_event::Action::Create || action == file_sync::sync_event::Action::Modify {
                        if let Ok(mut file) = tokio::fs::File::open(&path).await{
                            if tokio::io::copy(&mut file, &mut socket).await.is_ok(){
                                println!("[handle_connection-128]sent file data: {}", &file_path);
                            }
                        }
                    }    
                }
            }
        }        
    }
    }
}


async fn handle_phone_event(event: &file_sync::SyncEvent, socket: &mut tokio::net::TcpStream) {
    let normalized_path = event.file_path.trim_start_matches('/');
    let path = format!("./AuraSync/{}", normalized_path);
    let temp = format!("{}.tmp", path);
    println!("[handle_phone_event:142]{}", path);
    match event.action() {
        file_sync::sync_event::Action::Create | file_sync::sync_event::Action::Modify =>{
            let mut file = tokio::fs::File::create(&temp).await.unwrap();
            let mut remaining = event.file_size;
            let mut buf = vec![0u8; 65536];
            println!("[handle_phone_event:150] receiving file of size: {} buffer size {}", remaining,buf.len());
            while remaining >0 {
                let val_read =std::cmp::min(remaining , buf.len() as u64) as usize ;
                println!("[handle_phone_event:153] reading {} bytes from socket", val_read);
                let n = socket.read_exact(&mut buf[..val_read]).await.unwrap();
                file.write_all(&buf[..n]).await.unwrap();
                println!("[handle_phone_event:154] wrote {} bytes to file", n);
                remaining -= n as u64;
                println!("[handle_phone_event:155] remaining bytes to read: {}", remaining);
            }
            file.flush().await.unwrap();
            tokio::fs::rename(&temp, &path).await.unwrap();
            let normalized_path = event.file_path.trim_start_matches('/').to_string();
            SYNC_REGISTRY.insert(normalized_path, event.checksum.clone());
            println!("[handle_phone_event:160] written file: {}", path);
        },
        file_sync::sync_event::Action::Delete => {
            if std::fs::remove_file(&path).is_ok(){
                println!("[handle_phone_event:159] deleted file: {}", path);
            }
        },
        file_sync::sync_event::Action::Rename => {
        if !event.new_path.is_empty(){
                let new_path = format!("./AuraSync/{}",event.new_path);
                if std::fs::rename(&path,&new_path).is_ok(){
                    println!("[handle_phone_event:166]renamed file: {} to {}", path,new_path);
                }   
            }
        },
        file_sync::sync_event::Action::AttrChange => todo!(),
    }
}