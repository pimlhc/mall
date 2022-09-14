package com.chinasofti.mall.order.controller;

import com.alibaba.fastjson.JSONObject;
import com.chinasofti.mall.common.dto.Response;
import com.chinasofti.mall.common.vo.OrderVo;
import com.chinasofti.mall.order.dao.OrderMapper;
import com.chinasofti.mall.order.enums.OrderStatus;
import com.chinasofti.mall.order.form.OrderCreateForm;
import com.chinasofti.mall.order.intercepter.UserLoginInterceptor;
import com.chinasofti.mall.order.model.DaprSubscription;
import com.chinasofti.mall.order.model.SubscriptionData;
import com.chinasofti.mall.order.pojo.Order;
import com.chinasofti.mall.order.service.IOrderService;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Created by xuepeng@chinasofti.com
 */
@Slf4j
@RestController
public class OrderController {

	@Autowired
	private IOrderService orderService;

	@Autowired
	OrderMapper orderMapper;


	private static final String sqlBindingName = "sqldb";

	private static final HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_2)
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	private static String DAPR_HOST = System.getenv().getOrDefault("DAPR_HOST", "http://localhost");
	private static String DAPR_HTTP_PORT = System.getenv().getOrDefault("DAPR_HTTP_PORT", "3605");

	@PostMapping("/orders")
	public Response<OrderVo> create(@Valid @RequestBody OrderCreateForm form) {
		Integer userId= Integer.valueOf(UserLoginInterceptor.getUserId());

		return orderService.create(userId, form.getShippingId());
	}

	@GetMapping("/orders")
	public Response<PageInfo> list(@RequestParam Integer pageNum,
									 @RequestParam Integer pageSize) {
		return orderService.list(Integer.valueOf(UserLoginInterceptor.getUserId()), pageNum, pageSize);
	}

	@GetMapping("/orders/{orderNo}")
	public Response<OrderVo> detail(@PathVariable Long orderNo) {
		return orderService.detail(Integer.valueOf(UserLoginInterceptor.getUserId()), orderNo);
	}

	@GetMapping("/orders/uoid/{userid}/{orderNo}")
	public Response<OrderVo> orderVO(@PathVariable Integer userid,@PathVariable Long orderNo) {
		return orderService.detail(userid, orderNo);
	}

	@PutMapping("/orders/{orderNo}")
	public Response cancel(@PathVariable Long orderNo) {
		return orderService.cancel(Integer.valueOf(UserLoginInterceptor.getUserId()), orderNo);
	}
	@GetMapping(path = "/dapr/subscribe", produces = MediaType.APPLICATION_JSON_VALUE)
	public DaprSubscription[] getSubscription() {
		DaprSubscription daprSubscription = DaprSubscription.builder()
				.pubSubName("orderpubsub")
				.topic("orders")
				.route("daprOrders")
				.build();
		DaprSubscription[] arr = new DaprSubscription[]{daprSubscription};
		return arr;
	}

	@PostMapping(path = "/daprOrders", consumes = MediaType.ALL_VALUE)
	public ResponseEntity<?> processOrders(@RequestBody SubscriptionData<Order> body) {

		Order order = orderMapper.selectByOrderNo(Long.valueOf(body.getData().getOrderNo()));
		order.setStatus(OrderStatus.SUCCESS.getCode());
		orderMapper.updateByPrimaryKeySelective(order);
		return ResponseEntity.ok().build();
	}



	private static final String cronBindingPath = "/cron";

	@PostMapping(path = cronBindingPath, consumes = MediaType.ALL_VALUE)
	public void closeOrder() throws Exception {
		String daprUri = DAPR_HOST +":"+ DAPR_HTTP_PORT + "/v1.0/bindings/"+sqlBindingName;

		List<Order> orders = orderMapper.selectNoPayOrderTimeOut(OrderStatus.NOTPAY.getCode());
		orders.stream().forEach(order->{
			order.setStatus(OrderStatus.CLOSED.getCode());
			orderMapper.updateByPrimaryKeySelective(order);

			String sqlText = String.format(
			"update mall_order set status = %s where id = %s;",
					OrderStatus.CLOSED.getCode(),order.getId());

			System.out.println("sqltest"+sqlText);
			JSONObject command = new JSONObject();
			command.put("sql", sqlText);

			JSONObject payload = new JSONObject();
			payload.put("metadata", command);
			payload.put("operation", "exec");

			HttpRequest request = HttpRequest.newBuilder()
					.POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
					.uri(URI.create(daprUri))
					.header("Content-Type", "application/json")
					.build();

			try {
				HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			} catch (IOException e) {
				throw new RuntimeException(e);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}

		});
	}
}
