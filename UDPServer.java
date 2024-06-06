import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UDPServer {
    private static final int SERVER_PORT = 8888;
    private static final int MAX_PACKET_SIZE = 1024;
    private static final double PACKET_LOSS_RATE = 0.2;
    private static final ExecutorService threadPool = Executors.newCachedThreadPool(); // 使用线程池来处理客户端请求
    private static final byte IPV4 = 4;
    private static final byte IPV6 = 6;
    private static final int HEADER_SIZE = 2 + 2 + 1 + 8 + 1 + 16 + 8 + 4;
    private static final byte VERSION = 2;
    private static final int MESSAGE_LENGTH = 203;
    private static final String HANDSHAKE_INIT = "HANDSHAKE_INIT";
    private static final String HANDSHAKE_ACK = "HANDSHAKE_ACK";
    private static final String HANDSHAKE_CONFIRM = "HANDSHAKE_CONFIRM";
    private static final String CONNECTION_RELEASE_FIN = "CONNECTION_RELEASE_FIN";
    private static final String CONNECTION_RELEASE_ACK = "CONNECTION_RELEASE_ACK";

    public static void main(String[] args) {
        try {
            // 创建一个DatagramSocket实例，用于监听服务器端口SERVER_PORT上的UDP数据包
            DatagramSocket server_socket = new DatagramSocket(SERVER_PORT);
            System.out.println("服务器已启动...");
            Random random = new Random();

            while (true) {
                // 创建一个DatagramPacket实例，用于接收数据，大小限制为MAX_PACKET_SIZE
                DatagramPacket receive_packet = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
                // 通过server_socket接收数据，数据会被存储在receive_packet中
                server_socket.receive(receive_packet);
                // 解析接收到的数据包，将数据转换为Map格式
                Map<String, Object> receive_data_map = parsePacket(receive_packet.getData());
                // 获取发送数据包的客户端IP地址
                InetAddress client_address = receive_packet.getAddress();
                // 获取发送数据包的客户端端口号
                int client_port = receive_packet.getPort();


                String received_message = (String) receive_data_map.get("received_message");

                if (HANDSHAKE_INIT.equals(received_message)) {                      // 如果消息内容是握手初始化消息
                    // 处理握手过程
                    handleHandshake(server_socket, receive_data_map, client_address, client_port);
                } else if (CONNECTION_RELEASE_FIN.equals(received_message)) {       // 如果消息内容是连接释放请求
                    // 处理连接释放
                    connectionRelease(server_socket, client_address, client_port);
                } else {
                    // 模拟丢包
                    if (random.nextDouble() < PACKET_LOSS_RATE) {
                        System.out.println(client_address + ":" + client_port + ":序号 " + receive_data_map.get("sequence_number") + "的请求被丢弃，内容为 " + receive_data_map.get("received_message"));
                        continue;
                    }

                    threadPool.execute(() -> handleClient(server_socket, receive_data_map, client_address, client_port));
                    // 使用线程池处理客户端请求
                    //handleClient(server_socket, receive_data_map, client_address, client_port);
                    // 如果不是特殊的消息，则直接处理客户端请求
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 处理客户端发送的数据
    private static void handleClient(DatagramSocket server_socket, Map<String, Object> receive_data_map, InetAddress client_address, int client_port) {
        try {
            String respond_messages = "The response that the server sends to the client. ";

            Object[] send_data_object = getSendDate((Short) receive_data_map.get("sequence_number"), respond_messages, client_address, client_port);
            byte[] send_data = (byte[]) send_data_object[0];    // 发送数据的字节数组
            int packet_length = (int) send_data_object[1];      // 数据包的长度

            System.out.println(client_address + ":" + client_port + ":Received data with the serial number of " + receive_data_map.get("sequence_number"));

            // 创建发送数据包
            DatagramPacket send_packet = new DatagramPacket(send_data, packet_length, client_address, client_port);
            server_socket.send(send_packet);
        } catch (IOException e) {
            System.out.println("本次连接中断");
        }
    }

    // 处理握手过程
    private static void handleHandshake(DatagramSocket server_socket, Map<String, Object> receive_data_map, InetAddress client_address, int client_port) {
        try {
            // 第二次握手：发送连接确认
            Object[] ack_data = getSendDate((Short) receive_data_map.get("sequence_number"), HANDSHAKE_ACK, client_address, client_port);
            byte[] send_ack_data = (byte[]) ack_data[0];                                // 发送确认数据的字节数组
            int packet_ack_length = (int) ack_data[1];                                  // 确认数据包的长度
            // 创建发送确认数据包
            DatagramPacket ack_packet = new DatagramPacket(send_ack_data, packet_ack_length, client_address, client_port);
            server_socket.send(ack_packet);                                             // 发送确认数据包到客户端

            // 第三次握手：等待客户端的确认消息
            DatagramPacket confirm_packet = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
            server_socket.receive(confirm_packet);                                      // 接收客户端的确认消息
            Map<String, Object> confirmDataMap = parsePacket(confirm_packet.getData()); // 解析确认消息

            if (HANDSHAKE_CONFIRM.equals(confirmDataMap.get("received_message"))) {
                // 如果确认消息是预期的，则建立成功
                System.out.println("连接成功建立！");
            }
        } catch (IOException e) {
            System.out.println("握手过程出错");
        }
    }


    // 处理连接释放过程
    private static void connectionRelease(DatagramSocket server_socket, InetAddress client_address, int client_port) {
        try {
            // 发送确认消息以开始连接释放过程
            Object[] ack_response_object = getSendDate((short) 0, CONNECTION_RELEASE_ACK, client_address, client_port);
            byte[] send_ack_response_data = (byte[]) ack_response_object[0];        // 准备发送的确认消息数据
            int packet_ack_response_length = (int) ack_response_object[1];          // 确认消息数据包的长度
            DatagramPacket ack_confirm_packet = new DatagramPacket(send_ack_response_data, packet_ack_response_length, client_address, client_port);
            server_socket.send(ack_confirm_packet);                                 // 发送确认消息到客户端

            // 发送FIN消息请求释放连接
            Object[] fin_data = getSendDate((short) 0, CONNECTION_RELEASE_FIN, client_address, client_port);
            byte[] send_request_data = (byte[]) fin_data[0];                        // 准备发送的FIN请求消息数据
            int packet_request_length = (int) fin_data[1];                          // FIN请求消息数据包的长度
            DatagramPacket fin_packet = new DatagramPacket(send_request_data, packet_request_length, client_address, client_port);
            server_socket.send(fin_packet);                                         // 发送FIN请求消息到客户端

            server_socket.setSoTimeout(1000);
            // 尝试接收客户端的确认消息,无论这个消息是否收到都为断开连接
            try{
                DatagramPacket ack_packet = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
                server_socket.receive(ack_packet);                                      // 接收客户端的确认消息
                Map<String, Object> ack_data = parsePacket(ack_packet.getData());       // 解析确认消息
                if (CONNECTION_RELEASE_ACK.equals(ack_data.get("received_message"))) {
                    System.out.println("The client has released the connection\n");
                }
            }catch (SocketTimeoutException e) {
                // 如果超时，则忽略确认消息
                System.out.println("No acknowledgment received from the client, but the connection is considered released.\n");
            } finally {
                server_socket.setSoTimeout(0);
            }

        } catch (IOException e) {
            System.out.println("释放过程出错");
        }
    }

    // 获取IP地址的字节表示
    private static Object[] getAddressBytes(InetAddress address) {
        // 创建结果数组，用于存储地址类型和字节表示
        Object[] result = new Object[2];
        if (address instanceof Inet4Address) {
            // 如果是IPv4地址
            result[0] = IPV4;                           // 标记为IPv4
            byte[] ipv4_bytes = address.getAddress();   // 获取IPv4地址的字节表示
            byte[] address_bytes = new byte[16];        // 创建一个16字节的数组，用于存储IPv4地址的IPv6表示
            System.arraycopy(ipv4_bytes, 0, address_bytes, 0, ipv4_bytes.length); // 复制IPv4地址字节到新数组
            Arrays.fill(address_bytes, address_bytes.length, 16, (byte) 0x00);// 用0填充剩余的字节，以符合IPv6地址格式
            result[1] = address_bytes; // 存储IPv4地址的IPv6表示
            return result;
        }
        // 如果是IPv6地址
        result[0] = IPV6;                   // 标记为IPv6
        result[1] = address.getAddress();   // 直接存储IPv6地址的字节表示
        return result;
    }


    // 获取当前时间并以字节数组形式返回
    private static byte[] getCurrentTime() {
        // 获取当前时间
        LocalTime current_time = LocalTime.now();
        // 创建一个DateTimeFormatter格式化器，设置为hh:mm:ss格式
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        // 格式化当前时间为字符串
        String formatted_time = current_time.format(formatter);
        // 将字符串转换为字节数组
        return formatted_time.getBytes();
    }

    // 构造发送数据包，包括头部和数据部分
    private static Object[] getSendDate(short sequence_number, String send_messages, InetAddress address, int port) {
        byte[] send_data = send_messages.getBytes();        // 将发送消息转换为字节数组
        Object[] address_info = getAddressBytes(address);   // 获取地址字节数组和地址版本
        byte ip_version = (byte) address_info[0];           // IP版本号
        byte[] address_bytes = (byte[]) address_info[1];    // IP地址字节数组
        byte[] send_time = getCurrentTime();                // 获取发送时间
        int packet_length = HEADER_SIZE + send_data.length; // 计算报文长度

        // 使用ByteBuffer来构建报文
        ByteBuffer buffer = ByteBuffer.allocate(MESSAGE_LENGTH);
        buffer.putShort(sequence_number);                   // 2字节序列号
        buffer.put(VERSION);                                // 1字节版本号
        buffer.putLong(port);                               // 8字节目的端口号
        buffer.put(ip_version);                             // 1字节的IP版本号
        buffer.put(address_bytes);                          // 16字节的目的地址
        buffer.put(send_time);                              // 8字节发送时间
        buffer.putInt(packet_length);                       // 4字节消息总长度
        buffer.put(send_data);                              // 发送的数据

        byte[] packet_data = buffer.array(); // 获取ByteBuffer中的字节数组
        // 使用 Arrays.fill 来填充剩余的空间
        Arrays.fill(packet_data, buffer.position(), MESSAGE_LENGTH, (byte) 0x00);

        // 使用数组来返回两个值：数据包和长度
        Object[] result = new Object[2];
        result[0] = packet_data;
        result[1] = packet_length;
        return result;
    }

    // 解析收到的字节数组，提取头部信息和数据
    private static Map<String, Object> parsePacket(byte[] receivedPacketData) {
        Map<String, Object> result_map = new HashMap<>();           // 创建结果Map
        ByteBuffer buffer = ByteBuffer.wrap(receivedPacketData);    // 包装接收到的字节数组

        try {
            // 解析消息
            short sequence_number = buffer.getShort();               // 2字节序列号
            byte version = buffer.get();                             // 1字节版本号
            long client_port = buffer.getLong();                     // 8字节目的端口号
            byte ip_version = buffer.get();                          // 1字节的IP版本号

            byte[] address_bytes = new byte[16];                     // 16字节的目的地址
            buffer.get(address_bytes);

            byte[] send_time = new byte[8];                          // 8字节发送时间
            buffer.get(send_time);

            int message_length = buffer.getInt();                    // 4字节消息总长度
            byte[] send_data = new byte[(int) (message_length - HEADER_SIZE)];
            buffer.get(send_data);

            // 解析 IP 地址
            InetAddress address = null;
            if (ip_version == IPV4) {
                byte[] ipv4Bytes = Arrays.copyOfRange(address_bytes, 0, 4);
                address = InetAddress.getByAddress(ipv4Bytes);
            } else if (ip_version == IPV6) {
                address = InetAddress.getByAddress(address_bytes);
            }

            // 将解析结果放入 Map
            result_map.put("sequence_number", sequence_number);
            result_map.put("version", version);
            result_map.put("client_port", client_port);
            result_map.put("ip_version", ip_version);
            result_map.put("address", address);
            result_map.put("send_time", new String(send_time));
            result_map.put("message_length", message_length);
            result_map.put("received_message", new String(send_data));
        } catch (UnknownHostException e) {
            System.err.println("无法解析的地址或域名！");
        }
        return result_map;
    }

}
