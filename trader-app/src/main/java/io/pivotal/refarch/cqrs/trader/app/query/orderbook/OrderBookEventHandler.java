/*
 * Copyright (c) 2010-2012. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.pivotal.refarch.cqrs.trader.app.query.orderbook;

import io.pivotal.refarch.cqrs.trader.app.query.company.CompanyView;
import io.pivotal.refarch.cqrs.trader.app.query.company.CompanyViewRepository;
import io.pivotal.refarch.cqrs.trader.app.query.orders.trades.OrderBookView;
import io.pivotal.refarch.cqrs.trader.app.query.orders.trades.OrderView;
import io.pivotal.refarch.cqrs.trader.app.query.orders.transaction.TradeExecutedQueryRepository;
import io.pivotal.refarch.cqrs.trader.app.query.orders.transaction.TradeExecutedView;
import io.pivotal.refarch.cqrs.trader.coreapi.company.OrderBookAddedToCompanyEvent;
import io.pivotal.refarch.cqrs.trader.coreapi.orders.OrderBookId;
import io.pivotal.refarch.cqrs.trader.coreapi.orders.trades.*;
import io.pivotal.refarch.cqrs.trader.coreapi.orders.transaction.ExecutedTradesByOrderBookIdQuery;
import org.axonframework.config.ProcessingGroup;
import org.axonframework.eventhandling.EventHandler;
import org.axonframework.queryhandling.QueryHandler;
import org.axonframework.queryhandling.QueryUpdateEmitter;
import org.springframework.stereotype.Service;

import java.util.List;

// TODO introduce regular Unit Test for this class i.o. just an intergration test
@Service
@ProcessingGroup("trading")
public class OrderBookEventHandler {

    private static final String BUY = "Buy";
    private static final String SELL = "Sell";

    private final OrderBookViewRepository orderBookRepository;
    private final CompanyViewRepository companyRepository;
    private final TradeExecutedQueryRepository tradeExecutedRepository;
    private final QueryUpdateEmitter queryUpdateEmitter;

    public OrderBookEventHandler(OrderBookViewRepository orderBookRepository,
                                 CompanyViewRepository companyRepository,
                                 TradeExecutedQueryRepository tradeExecutedRepository,
                                 QueryUpdateEmitter queryUpdateEmitter) {
        this.orderBookRepository = orderBookRepository;
        this.companyRepository = companyRepository;
        this.tradeExecutedRepository = tradeExecutedRepository;
        this.queryUpdateEmitter = queryUpdateEmitter;
    }

    @EventHandler
    public void on(OrderBookAddedToCompanyEvent event) {
        CompanyView companyView = companyRepository.getOne(event.getCompanyId().getIdentifier());

        OrderBookView orderBookView = new OrderBookView();
        orderBookView.setCompanyIdentifier(event.getCompanyId().getIdentifier());
        orderBookView.setCompanyName(companyView.getName());
        orderBookView.setIdentifier(event.getOrderBookId().getIdentifier());

        orderBookRepository.save(orderBookView);
    }

    @EventHandler
    public void on(BuyOrderPlacedEvent event) {
        OrderBookView orderBook = orderBookRepository.getOne(event.getOrderBookId().getIdentifier());

        OrderView buyOrder = createPlacedOrder(event, BUY);
        orderBook.getBuyOrders().add(buyOrder);

        orderBookRepository.save(orderBook);

        queryUpdateEmitter.emit(OrderBookByIdQuery.class,
                                q -> q.getOrderBookId().getIdentifier().equals(orderBook.getIdentifier()),
                                eagerInit(orderBook));

    }

    @EventHandler
    public void on(SellOrderPlacedEvent event) {
        OrderBookView orderBook = orderBookRepository.getOne(event.getOrderBookId().getIdentifier());

        OrderView sellOrder = createPlacedOrder(event, SELL);
        orderBook.getSellOrders().add(sellOrder);

        orderBookRepository.save(orderBook);

        queryUpdateEmitter.emit(OrderBookByIdQuery.class,
                                q -> q.getOrderBookId().getIdentifier().equals(orderBook.getIdentifier()),
                                eagerInit(orderBook));

    }

    @EventHandler
    public void on(TradeExecutedEvent event) {
        OrderId buyOrderId = event.getBuyOrderId();
        OrderId sellOrderId = event.getSellOrderId();

        OrderBookId orderBookIdentifier = event.getOrderBookId();
        OrderBookView orderBookView = orderBookRepository.getOne(orderBookIdentifier.getIdentifier());

        TradeExecutedView tradeExecutedView = new TradeExecutedView();
        tradeExecutedView.setCompanyName(orderBookView.getCompanyName());
        tradeExecutedView.setOrderBookId(orderBookView.getIdentifier());
        tradeExecutedView.setTradeCount(event.getTradeCount());
        tradeExecutedView.setTradePrice(event.getTradePrice());

        tradeExecutedRepository.save(tradeExecutedView);

        OrderView foundBuyOrder = null;
        for (OrderView order : orderBookView.getBuyOrders()) {
            if (order.getIdentifier().equals(buyOrderId.getIdentifier())) {
                long itemsRemaining = order.getItemsRemaining();
                order.setItemsRemaining(itemsRemaining - event.getTradeCount());
                foundBuyOrder = order;
                break;
            }
        }
        if (null != foundBuyOrder && foundBuyOrder.getItemsRemaining() == 0) {
            orderBookView.getBuyOrders().remove(foundBuyOrder);
        }
        OrderView foundSellOrder = null;
        for (OrderView order : orderBookView.getSellOrders()) {
            if (order.getIdentifier().equals(sellOrderId.getIdentifier())) {
                long itemsRemaining = order.getItemsRemaining();
                order.setItemsRemaining(itemsRemaining - event.getTradeCount());
                foundSellOrder = order;
                break;
            }
        }
        if (null != foundSellOrder && foundSellOrder.getItemsRemaining() == 0) {
            orderBookView.getSellOrders().remove(foundSellOrder);
        }
        orderBookRepository.save(orderBookView);

        queryUpdateEmitter.emit(OrderBookByIdQuery.class,
                                q -> q.getOrderBookId().getIdentifier().equals(orderBookView.getIdentifier()),
                                eagerInit(orderBookView));
    }

    private OrderView createPlacedOrder(AbstractOrderPlacedEvent event, String type) {
        OrderView entry = new OrderView();

        entry.setIdentifier(event.getOrderId().getIdentifier());
        entry.setItemsRemaining(event.getTradeCount());
        entry.setTradeCount(event.getTradeCount());
        entry.setUserId(event.getPortfolioId().getIdentifier());
        entry.setType(type);
        entry.setItemPrice(event.getItemPrice());

        return entry;
    }

    @QueryHandler
    public OrderBookView find(OrderBookByIdQuery query) {
        return eagerInit(orderBookRepository.getOne(query.getOrderBookId().getIdentifier()));
    }

    @QueryHandler
    public List<OrderBookView> find(OrderBooksByCompanyIdQuery query) {
        return eagerInit(orderBookRepository.findByCompanyIdentifier(query.getCompanyId().getIdentifier()));
    }

    @QueryHandler
    public List<TradeExecutedView> find(ExecutedTradesByOrderBookIdQuery query) {
        return tradeExecutedRepository.findByOrderBookId(query.getOrderBookId().getIdentifier());
    }

    private OrderBookView eagerInit(OrderBookView orderBookView) {
        orderBookView.getBuyOrders().size();
        orderBookView.getSellOrders().size();
        return orderBookView;
    }

    private List<OrderBookView> eagerInit(List<OrderBookView> orderBookViews) {
        for (OrderBookView orderBookView : orderBookViews) {
            eagerInit(orderBookView);
        }
        return orderBookViews;
    }

}
