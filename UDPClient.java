import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class UDPClient {
    private static final int TIMEOUT = 100; // 毫秒
    private static final int MAX_PACKET_SIZE = 1024;
    private static final int MAX_RETRY_COUNT = 3;
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
        if (args.length != 2) {
            System.out.println("参数格式错误，需要提供目标地址和目标端口号！");
            System.exit(1);
        }
        try {
            String server_ip = args[0];
            int server_port = Integer.parseInt(args[1]);
            InetAddress server_address = InetAddress.getByName(server_ip);

            // 检查端口号是否在有效范围内
            if (server_port < 0 || server_port > 65535) {
                System.err.println("端口号必须在 0 到 65535 之间。");
                System.exit(1);
            }

            // 创建一个DatagramSocket实例，用于发送和接收UDP数据包
            DatagramSocket client_socket = new DatagramSocket();

            // 模拟TCP连接建立
            System.out.println("正在建立连接...");
            if (!performHandshake(client_socket, server_address, server_port)) {
                // 执行握手过程，如果失败，输出错误信息并关闭socket
                System.out.println("连接建立失败");
                client_socket.close();
                System.exit(1);
            }
            System.out.println("连接已建立，开始传输数据...");

            client_socket.setSoTimeout(TIMEOUT);  // 设置超时时间
            int received_udppackets_count = 0;
            int total_sent_udppackets_count = 0;
            long min_rtt = Long.MAX_VALUE;
            long max_rtt = Long.MIN_VALUE;
            long all_rtt = 0;
            String first_send_time = null;
            String last_send_time = null;
            List<Long> rtt_list = new ArrayList<>();
            int send_times = getSendTimes();
            // 使用循环发送多个报文，序列号从1到send_times
            for (short i = 1; i <= send_times; i++) {
                String send_messages = "A request that a client sends to a server.";
                // 获取当前发送时间（毫秒级）
                long send_time_ms = System.currentTimeMillis();
                // 调用getSendDate方法获取封装好的报文数据和报文长度
                Object[] send_data_object = getSendDate(i, send_messages, server_address, server_port);
                byte[] send_data = (byte[]) send_data_object[0]; // 报文数据
                int packet_length = (int) send_data_object[1];  // 报文长度

                int retry_count = 0;  // 重传计数器，用于记录当前报文的重传次数
                boolean response_received = false; // 标记是否收到了期待的响应

                // 在未达到最大重传次数且未收到期待的响应时，执行重传逻辑
                while (retry_count < MAX_RETRY_COUNT && !response_received) {
                    // 构造发送报文的数据包
                    DatagramPacket send_packet = new DatagramPacket(send_data, packet_length, server_address, server_port);
                    // 发送数据包到服务器
                    client_socket.send(send_packet);  // 发送数据包
                    total_sent_udppackets_count++;    // 统计发送的数据包数量
                    System.out.println("Sent packet with sequence number " + i + ", attempt " + (retry_count + 1));

                    // 循环接收响应，直到收到预期的序列号或达到重传次数上限
                    while (true) {
                        // 构造接收响应的数据包
                        DatagramPacket receive_packet = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
                        try {
                            // 接收服务器的响应
                            client_socket.receive(receive_packet);
                            // 解析接收到的响应数据
                            Map<String, Object> receive_data_map = parsePacket(receive_packet.getData());
                            short sequence_number = (short) receive_data_map.get("sequence_number");

                            // 检查响应中的序列号是否与当前期望的序列号匹配
                            if (sequence_number == i) {
                                long rtt = System.currentTimeMillis() - send_time_ms;
                                min_rtt = Math.min(rtt, min_rtt);
                                max_rtt = Math.max(rtt, max_rtt);
                                all_rtt += rtt;
                                rtt_list.add(rtt);
                                System.out.println("A response message with sequence number " + sequence_number + " has been received");
                                System.out.println("RTT: " + rtt + "ms");
                                System.out.println("The time of the server's response this time: " + receive_data_map.get("send_time"));
                                response_received = true;
                                if (received_udppackets_count == 0) first_send_time = receive_data_map.get("send_time").toString();
                                last_send_time = receive_data_map.get("send_time").toString();
                                received_udppackets_count++;
                                break;
                            } else {
                                // 如果收到的响应序列号与当前期望的序列号不匹配，则打印提示信息
                                System.out.println("Received packet with unexpected sequence number " + sequence_number + ", expected " + i);
                            }
                        } catch (SocketTimeoutException ste) {
                            // 如果接收超时，则打印提示信息，增加重传计数器，并跳出内部接收循环以重试发送
                            System.out.println("sequence " + i + ", request time out");
                            retry_count++;
                            break; // 跳出内部接收循环并重试发送
                        }
                    }
                }

                // 如果在最大重传次数内仍未收到期待的响应，则打印提示信息
                if (!response_received) {
                    System.out.println("Serial number " + i + " has reached the upper limit of the number of retransmissions and will not be retransmitted");
                }
                System.out.println();
            }


            // 模拟TCP连接释放
            connectionRelease(client_socket, server_address, server_port);

            double packet_loss_rate = 1 - (double) received_udppackets_count / total_sent_udppackets_count;
            double average_rtt = (received_udppackets_count > 0) ? (double) all_rtt / received_udppackets_count : 0;
            double rtt_standard_deviation = getRTTStandardDeviation(received_udppackets_count, average_rtt, rtt_list);

            System.out.println("接收到的udp packets的数量: " + received_udppackets_count);
            System.out.printf("丢包率: %.2f%%\n", packet_loss_rate * 100);
            System.out.println("Min RTT: " + min_rtt + "ms");
            System.out.println("Max RTT: " + max_rtt + "ms");
            System.out.println("Average RTT: " + average_rtt + "ms");
            System.out.println("RTT Standard Deviation: " + rtt_standard_deviation + "ms");
            System.out.println("Server的最初响应时间: " + first_send_time + ", 最后响应时间: " + last_send_time);
            System.out.println("Server的整体响应时间: " + calculateTimeDifference(first_send_time, last_send_time));
        } catch (UnknownHostException e) {
            System.err.println("无法解析的地址或域名！");
        } catch (NumberFormatException e) {
            System.err.println("端口号应该是整数！");
        } catch (IOException e) {
            System.out.println("连接中断！");
        }
    }

    // 获取用户输入的发送次数
    private static int getSendTimes() {
        // 创建一个Scanner对象用于读取用户输入
        Scanner scanner = new Scanner(System.in);
        int send_times = 0;
        while (true) {
            try {
                System.out.print("请输入发送的次数："); // 提示用户输入发送次数
                send_times = scanner.nextInt();     // 读取用户输入的整数
                if (send_times > 0) break;          // 如果输入有效（大于0），则退出循环
                System.out.println("输入的次数必须大于0，请重新输入。"); // 提示用户输入错误
            } catch (InputMismatchException e) {
                System.out.println("输入无效，请输入一个正整数。"); // 输入了非整数内容，提示用户输入错误
                scanner.next();                     // 清除无效输入
            }
        }
        // 关闭Scanner对象
        scanner.close();
        return send_times;
    }

    // 计算RTT标准差
    private static double getRTTStandardDeviation(int received_udppackets_count, double average_rtt, List<Long> rtt_list) {
        // 如果接收到了UDP数据包数量则进行计算
        if (received_udppackets_count > 0) {
            double sum_of_squared_differences = 0;
            for (long rtt : rtt_list) {
                // 计算每个RTT与平均RTT的平方差
                sum_of_squared_differences += Math.pow(rtt - average_rtt, 2);
            }
            // 计算RTT标准差
            return Math.sqrt(sum_of_squared_differences / received_udppackets_count);
        }
        // 如果接收到的UDP数据包数量为0，返回0
        return 0;
    }

    // 执行UDP协议的握手过程
    private static boolean performHandshake(DatagramSocket client_socket, InetAddress server_address, int server_port) throws IOException {
        boolean handshake_successful = false;       // 初始化握手成功标志为false
        client_socket.setSoTimeout(TIMEOUT * 10);   // 设置socket的超时时间

        // 第一次握手：发送连接请求
        Object[] init_data = getSendDate((short) 0, HANDSHAKE_INIT, server_address, server_port);
        byte[] send_request_data = (byte[]) init_data[0];   // 消息字节数据
        int packet_request_length = (int) init_data[1];     // 消息数据包的长度
        DatagramPacket init_packet = new DatagramPacket(send_request_data, packet_request_length, server_address, server_port);
        client_socket.send(init_packet);

        // 第二次握手：接收连接确认
        DatagramPacket ack_packet = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
        client_socket.receive(ack_packet);
        Map<String, Object> ack_data = parsePacket(ack_packet.getData());
        if (HANDSHAKE_ACK.equals(ack_data.get("received_message"))) {
            // 第三次握手：发送确认接收
            Object[] confirm_data = getSendDate((short) 0, HANDSHAKE_CONFIRM, server_address, server_port);
            byte[] send_confirm_data = (byte[]) confirm_data[0];
            int packet_confirm_length = (int) confirm_data[1];
            DatagramPacket confirm_packet = new DatagramPacket(send_confirm_data, packet_confirm_length, server_address, server_port);
            client_socket.send(confirm_packet);
            handshake_successful = true;
        }
        return handshake_successful;
    }


    private static void connectionRelease(DatagramSocket client_socket, InetAddress server_address, int server_port) throws IOException {
            // 发送关闭连接请求
            Object[] fin_data = getSendDate((short) 0, CONNECTION_RELEASE_FIN, server_address, server_port);
            byte[] send_request_data = (byte[]) fin_data[0];
            int packet_request_length = (int) fin_data[1];
            DatagramPacket fin_packet = new DatagramPacket(send_request_data, packet_request_length, server_address, server_port);
            client_socket.send(fin_packet);

            // 等待服务端确认关闭请求
            DatagramPacket ack_packet = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
            client_socket.receive(ack_packet);
            Map<String, Object> ack_data = parsePacket(ack_packet.getData());
            if (CONNECTION_RELEASE_ACK.equals(ack_data.get("received_message"))) {
                System.out.println("Received ACK from server.");
            }

            // 等待服务端关闭连接
            DatagramPacket close_packet = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
            client_socket.receive(close_packet);
            Map<String, Object> close_data = parsePacket(close_packet.getData());
            if (CONNECTION_RELEASE_FIN.equals(close_data.get("received_message"))) {
                System.out.println("Received CLOSE from server. Closing connection...");
            }

            // 发送确认消息
            Object[] ack_request_object = getSendDate((short) 0, CONNECTION_RELEASE_ACK, server_address, server_port);
            byte[] send_ack_request_data = (byte[]) ack_request_object[0];
            int packet_ack_request_length = (int) ack_request_object[1];
            DatagramPacket ack_confirm_packet = new DatagramPacket(send_ack_request_data, packet_ack_request_length, server_address, server_port);
            client_socket.send(ack_confirm_packet);

            // 关闭连接
            client_socket.close();
    }
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
    public static Map<String, Object> parsePacket(byte[] receivedPacketData) {
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
    private static String calculateTimeDifference(String time1, String time2) {
        if (time1 == null) return "00:00:00";
        // 定义时间格式
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");

        // 将字符串时间转换为LocalTime对象
        LocalTime localTime1 = LocalTime.parse(time1, formatter);
        LocalTime localTime2 = LocalTime.parse(time2, formatter);

        // 计算时间差
        Duration duration = Duration.between(localTime1, localTime2);

        // 将时间差转换回"hh:mm:ss"格式
        long hours = duration.toHours();
        long minutes = duration.toMinutes() % 60;
        long seconds = duration.getSeconds() % 60;

        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
