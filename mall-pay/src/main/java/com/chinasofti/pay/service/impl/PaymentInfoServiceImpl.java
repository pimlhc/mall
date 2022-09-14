package com.chinasofti.pay.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chinasofti.pay.entity.PaymentInfo;
import com.chinasofti.pay.enums.PayPlatformEnum;
import com.chinasofti.pay.mapper.PaymentInfoMapper;
import com.chinasofti.pay.service.PaymentInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

@Service
@Slf4j
public class PaymentInfoServiceImpl extends ServiceImpl<PaymentInfoMapper, PaymentInfo> implements PaymentInfoService {


    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String PUBSUB_NAME = "orderpubsub";
    private static final String TOPIC = "orders";
    private static String DAPR_HOST = System.getenv().getOrDefault("DAPR_HOST", "http://localhost");
    private static String DAPR_HTTP_PORT = System.getenv().getOrDefault("DAPR_HTTP_PORT", "3506");


    @Autowired
    PaymentInfoMapper paymentInfoMapper;

    @Autowired
    private AmqpTemplate amqpTemplate;

    @Override
    public void create(String orderNo, Integer amount) {
        PaymentInfo paymentInfo = new PaymentInfo();
        paymentInfo.setOrderNo(orderNo);
        paymentInfo.setPaymentType(PayPlatformEnum.WX.getName());
        paymentInfo.setPayerTotal(amount);
        paymentInfo.setTransactionId(UUID.randomUUID().toString());
        paymentInfo.setTradeType("NATIVE");
        paymentInfo.setTradeState("SUCCESS");

        paymentInfoMapper.insert(paymentInfo);

        String uri = DAPR_HOST +":"+ DAPR_HTTP_PORT + "/v1.0/publish/"+PUBSUB_NAME+"/"+TOPIC;

        JSONObject obj = new JSONObject();
        obj.put("orderNo", orderNo);

        HttpRequest request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(obj.toString()))
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        log.info("Published data: {}", orderNo);

    }
}
