package com.zosh.service.impl;

import com.zosh.domain.OrderStatus;
import com.zosh.domain.PaymentType;
import com.zosh.exception.UserException;
import com.zosh.mapper.OrderMapper;
import com.zosh.modal.Branch;
import com.zosh.modal.Order;
import com.zosh.modal.OrderItem;
import com.zosh.modal.Product;
import com.zosh.modal.User;
import com.zosh.payload.dto.OrderDTO;
import com.zosh.repository.BranchRepository;
import com.zosh.repository.OrderRepository;
import com.zosh.repository.ProductRepository;
import com.zosh.service.InventoryService;
import com.zosh.service.OrderService;
import com.zosh.service.UserService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final BranchRepository branchRepository;
    private final UserService userService;
    private final InventoryService inventoryService;

    @Override
    @Transactional
    public OrderDTO createOrder(OrderDTO dto) throws UserException {

        User cashier = userService.getCurrentUser();
        Branch branch = cashier.getBranch();
        if (branch == null) throw new UserException("Cashier's branch is null");

        // ✅ Generate UUID if frontend didn't provide one
        if (dto.getIdempotencyKey() == null || dto.getIdempotencyKey().isEmpty()) {
            dto.setIdempotencyKey(UUID.randomUUID().toString());
        }

        // ✅ Check for duplicate order using idempotencyKey
        Optional<Order> existingOrder = orderRepository.findByIdempotencyKey(dto.getIdempotencyKey());
        if (existingOrder.isPresent()) {
            return OrderMapper.toDto(existingOrder.get());
        }

        // Build the order entity
        Order order = Order.builder()
                .branch(branch)
                .cashier(cashier)
                .customer(dto.getCustomer())
                .paymentType(dto.getPaymentType())
                .idempotencyKey(dto.getIdempotencyKey())
                .build();

        // Map DTO items to OrderItems
        List<OrderItem> orderItems = dto.getItems().stream().map(itemDto -> {
            Product product = productRepository.findById(itemDto.getProductId())
                    .orElseThrow(() -> new EntityNotFoundException("Product not found"));

            return OrderItem.builder()
                    .product(product)
                    .quantity(itemDto.getQuantity())
                    .price(product.getSellingPrice() * itemDto.getQuantity())
                    .order(order)
                    .build();
        }).toList();

        double total = orderItems.stream().mapToDouble(OrderItem::getPrice).sum();
        order.setTotalAmount(total);
        order.setItems(orderItems);

        // Save order first
        Order savedOrder = orderRepository.save(order);

        // ✅ Reduce inventory for each order item
        try {
            for (OrderItem item : savedOrder.getItems()) {
                inventoryService.reduceInventoryForOrder(
                        branch.getId(),
                        item.getProduct().getId(),
                        item.getQuantity()
                );
            }
        } catch (UserException e) {
            // Transaction rollback if inventory reduction fails
            throw new UserException("Order creation failed: " + e.getMessage());
        }

        return OrderMapper.toDto(savedOrder);
    }

    @Override
    public OrderDTO getOrderById(Long id) {
        return orderRepository.findById(id)
                .map(OrderMapper::toDto)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
    }

    @Override
    public List<OrderDTO> getOrdersByBranch(Long branchId,
                                            Long customerId,
                                            Long cashierId,
                                            PaymentType paymentType,
                                            OrderStatus status) {
        return orderRepository.findByBranchId(branchId).stream()
                .filter(order -> customerId == null || (order.getCustomer() != null &&
                        order.getCustomer().getId().equals(customerId)))
                .filter(order -> cashierId == null || (order.getCashier() != null &&
                        order.getCashier().getId().equals(cashierId)))
                .filter(order -> paymentType == null || order.getPaymentType() == paymentType)
                .map(OrderMapper::toDto)
                .sorted((o1, o2) -> o2.getCreatedAt().compareTo(o1.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @Override
    public List<OrderDTO> getOrdersByCashier(Long cashierId) {
        return orderRepository.findByCashierId(cashierId).stream()
                .map(OrderMapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteOrder(Long id) {
        if (!orderRepository.existsById(id)) {
            throw new EntityNotFoundException("Order not found");
        }
        orderRepository.deleteById(id);
    }

    @Override
    public List<OrderDTO> getTodayOrdersByBranch(Long branchId) {
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        return orderRepository.findByBranchIdAndCreatedAtBetween(branchId, start, end)
                .stream()
                .map(OrderMapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<OrderDTO> getOrdersByCustomerId(Long customerId) {
        return orderRepository.findByCustomerId(customerId).stream()
                .map(OrderMapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<OrderDTO> getTop5RecentOrdersByBranchId(Long branchId) {
        branchRepository.findById(branchId)
                .orElseThrow(() -> new EntityNotFoundException("Branch not found with ID: " + branchId));

        return orderRepository.findTop5ByBranchIdOrderByCreatedAtDesc(branchId).stream()
                .map(OrderMapper::toDto)
                .collect(Collectors.toList());
    }
}
