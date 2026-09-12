import {get, post} from "@/net";

export const apiActivityList = (success, failure, error) => failure
    ? get('/api/activity/list', success, failure, error)
    : get('/api/activity/list', success)

export const apiActivityGrab = (data, success, failure) =>
    post('/api/activity/grab', data, success, failure)

export const apiActivityMyOrders = (success, failure, error) => failure
    ? get('/api/activity/my-orders', success, failure, error)
    : get('/api/activity/my-orders', success)
