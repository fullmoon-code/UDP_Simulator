import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UDPServer {
    private static final int SERVER_PORT = 1234;
    private static final int MAX_PACKET_SIZE = 1024;
    private static final double PACKET_LOSS_RATE = 0.2;
    private static final ExecutorService threadPool = Executors.newCachedThreadPool(); // 使用线程池来处理客户端请求
    private static final byte IPV4 = 4;
    private static final byte IPV6 = 6;
    private static final int HEADER_SIZE = 2 + 2 + 1 + 8 + 1 + 16 + 8 + 4;
    private static final byte VERSION = 2;
    private static final int MESSAGE_LENGTH = 203;
    public static final String HANDSHAKE_INIT = "HANDSHAKE_INIT";
    public static final String HANDSHAKE_ACK = "HANDSHAKE_ACK";
    public static final String HANDSHAKE_CONFIRM = "HANDSHAKE_CONFIRM";
    public static final String CONNECTION_RELEASE_FIN = "CONNECTION_RELEASE_FIN";
    public static final String CONNECTION_RELEASE_ACK = "CONNECTION_RELEASE_ACK";

    public static void main(String[] args) {
        try {
            DatagramSocket server_socket = new DatagramSocket(SERVER_PORT);
            System.out.println("服务器已启动...");
            while (true) {
                DatagramPacket receive_packet = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
                server_socket.receive(receive_packet);
                Map<String, Object> receive_data_map = parsePacket(receive_packet.getData());
                InetAddress client_address = receive_packet.getAddress();
                int client_port = receive_packet.getPort();

                String received_message = (String) receive_data_map.get("received_message");
                if (HANDSHAKE_INIT.equals(received_message)) {
                    // 第一次握手：接收到客户端的连接请求
                    handleHandshake(server_socket, receive_data_map, client_address, client_port);
                } else if (CONNECTION_RELEASE_FIN.equals(received_message)) {
                    connectionRelease(server_socket, client_address, client_port);
                } else {
                    SecureRandom secureRandom = new SecureRandom();
                    double randomNumber = secureRandom.nextDouble();
                    // 模拟丢包
                    if (randomNumber < PACKET_LOSS_RATE) {
                        System.out.println(client_address + ":" + client_port + ":序号 " + receive_data_map.get("sequence_number") + "的请求被丢弃，内容为 "+ receive_data_map.get("received_message"));
                        continue; // 如果丢包，则直接进入下一次循环，不处理该数据包
                    }

                    threadPool.execute(() -> handleClient(server_socket, receive_data_map, client_address, client_port));
                    //handleClient(server_socket, receive_data_map, client_address, client_port);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private static void handleClient(DatagramSocket server_socket, Map<String, Object> receive_data_map, InetAddress client_address, int client_port) {
        // 给客户端的回应
        String respond_messages = "The response that the server sends to the client. " + receive_data_map.get("sequence_number");
        Object[] send_data_object = getSendDate((Short) receive_data_map.get("sequence_number"), respond_messages, client_address, client_port);
        byte[] send_data = (byte[]) send_data_object[0];
        int packet_length = (int) send_data_object[1];
        System.out.println(client_address + ":" + client_port + ":Received data with the serial number of " + receive_data_map.get("sequence_number"));
        DatagramPacket send_packet = new DatagramPacket(send_data, packet_length, client_address, client_port);
        try {
            server_socket.send(send_packet);
        } catch (IOException e) {
            System.out.println("本次连接中断");
        }
    }
    private static void handleHandshake(DatagramSocket server_socket, Map<String, Object> receive_data_map, InetAddress client_address, int client_port) {
        try {
            // 第二次握手：发送连接确认
            Object[] ack_data = getSendDate((Short) receive_data_map.get("sequence_number"), HANDSHAKE_ACK, client_address, client_port);
            byte[] send_ack_data = (byte[]) ack_data[0];
            int packet_ack_length = (int) ack_data[1];
            DatagramPacket ack_packet = new DatagramPacket(send_ack_data, packet_ack_length, client_address, client_port);
            server_socket.send(ack_packet);
            // 第三次握手：等待客户端的确认消息
            DatagramPacket confirm_packet = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
            server_socket.receive(confirm_packet);
            Map<String, Object> confirmDataMap = parsePacket(confirm_packet.getData());

            if (HANDSHAKE_CONFIRM.equals(confirmDataMap.get("received_message"))) {
                System.out.println("连接成功建立！");
            }
        } catch (IOException e) {
            System.out.println("握手过程出错");
        }
    }
    private static void connectionRelease(DatagramSocket server_socket, InetAddress client_address, int client_port) {

        try {
            // 发送确认消息
            Object[] ack_response_object = getSendDate((short) 0, CONNECTION_RELEASE_ACK, client_address, client_port);
            byte[] send_ack_response_data = (byte[]) ack_response_object[0];
            int packet_ack_response_length = (int) ack_response_object[1];
            DatagramPacket ack_confirm_packet = new DatagramPacket(send_ack_response_data, packet_ack_response_length, client_address, client_port);
            server_socket.send(ack_confirm_packet);

            Object[] fin_data = getSendDate((short) 0, CONNECTION_RELEASE_FIN, client_address, client_port);
            byte[] send_request_data = (byte[]) fin_data[0];
            int packet_request_length = (int) fin_data[1];
            DatagramPacket fin_packet = new DatagramPacket(send_request_data, packet_request_length, client_address, client_port);
            server_socket.send(fin_packet);

            // 等待客户端确认关闭请求
            DatagramPacket ack_packet = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
            server_socket.receive(ack_packet);
            Map<String, Object> ack_data = parsePacket(ack_packet.getData());
            if (CONNECTION_RELEASE_ACK.equals(ack_data.get("received_message"))) {
                System.out.println("The client has released the connection\n");
            }

        } catch (IOException e) {
            System.out.println("释放过程出错");
        }
    }
    private static Object[] getAddressBytes(InetAddress address){
        Object[] result = new Object[2];
        if (address instanceof Inet4Address) {
            //IPV4
            result[0] = IPV4;
            byte[] ipv4_bytes = address.getAddress();
            byte[] address_bytes = new byte[16];
            System.arraycopy(ipv4_bytes, 0, address_bytes, 0, ipv4_bytes.length);
            Arrays.fill(address_bytes, address_bytes.length, 16, (byte) 0x00);
            result[1] = address_bytes;
            return result;
        }
        //IPV6
        result[0] = IPV6;
        result[1] = address.getAddress();
        return result;
    }
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

    //封装将要发送的字节数组
    public static Object[] getSendDate(short sequence_number, String send_messages, InetAddress address, int port){
        byte[] send_data = send_messages.getBytes();
        Object[] address_info = getAddressBytes(address);
        byte ip_version = (byte) address_info[0];
        byte[] address_bytes = (byte[]) address_info[1];
        byte[] send_time = getCurrentTime();

        // 使用ByteBuffer来构建报文
        ByteBuffer buffer = ByteBuffer.allocate(MESSAGE_LENGTH);
        buffer.putShort(sequence_number);                   // 2字节序列号
        buffer.put(VERSION);                                // 1字节版本号
        buffer.putLong(port);                               // 8字节目的端口号
        buffer.put(ip_version);                             // 1字节的IP版本号
        buffer.put(address_bytes);                          // 16字节的目的地址
        buffer.put(send_time);                              // 8字节发送时间
        // 计算报文长度
        int packet_length = HEADER_SIZE + send_data.length;
        buffer.putInt(packet_length);                       // 4字节消息总长度
        buffer.put(send_data);
        byte[] packet_data = buffer.array();
        // 使用 Arrays.fill 来填充剩余的空间
        Arrays.fill(packet_data, buffer.position(), MESSAGE_LENGTH, (byte) 0x00);

        // 使用数组来返回两个值
        Object[] result = new Object[2];
        result[0] = packet_data;
        result[1] = packet_length;
        return result;
    }
    //解析收到的字节数组
    public static Map<String, Object> parsePacket(byte[] receivedPacketData) {
        Map<String, Object> result_map = new HashMap<>();
        ByteBuffer buffer = ByteBuffer.wrap(receivedPacketData);
        try {
            // 解析消息
            short sequence_number = buffer.getShort();                // 2字节序列号
            byte version = buffer.get();                              // 1字节版本号
            long client_port = buffer.getLong();                      // 8字节目的端口号
            byte ip_version = buffer.get();                           // 1字节的IP版本号
            byte[] address_bytes = new byte[16];                      // 16字节的目的地址
            buffer.get(address_bytes);
            byte[] send_time = new byte[8];                           // 8字节发送时间
            buffer.get(send_time);
            int message_length = buffer.getInt();                     //4字节消息总长度
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

            // 解析发送的数据
            String received_message = new String(send_data);

            // 将解析结果放入 Map
            result_map.put("sequence_number", sequence_number);
            result_map.put("version", version);
            result_map.put("client_port", client_port);
            result_map.put("ip_version", ip_version);
            result_map.put("address", address);
            result_map.put("send_time", new String(send_time));
            result_map.put("message_length", message_length);
            result_map.put("received_message", received_message);
        } catch (UnknownHostException e) {
            System.err.println("无法解析的地址或域名！");
        }
        return result_map;
    }
}
