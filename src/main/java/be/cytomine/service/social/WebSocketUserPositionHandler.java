package be.cytomine.service.social;

import be.cytomine.domain.image.ImageInstance;
import be.cytomine.domain.security.SecUser;
import be.cytomine.domain.social.LastUserPosition;
import be.cytomine.exceptions.ServerException;
import be.cytomine.service.CytomineWebSocketHandler;
import be.cytomine.service.image.ImageInstanceService;
import be.cytomine.service.image.SliceInstanceService;
import be.cytomine.service.security.SecUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class WebSocketUserPositionHandler extends CytomineWebSocketHandler {

    public static Map<ConcurrentWebSocketSessionDecorator, ConcurrentWebSocketSessionDecorator[]> sessionsTracked = new ConcurrentHashMap<>();

    // sessionsTracked key -> "broadcastSessionId/imageId"
    public static Map<String, ConcurrentWebSocketSessionDecorator> sessionsBroadcast = new ConcurrentHashMap<>();

    // sessions key -> "userId"
    public static Map<String, ConcurrentWebSocketSessionDecorator[]> sessions = new ConcurrentHashMap<>();

    @Autowired
    UserPositionService userPositionService;

    @Autowired
    ImageInstanceService imageInstanceService;

    @Autowired
    SliceInstanceService sliceInstanceService;

    @Autowired
    SecUserService secUserService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions = super.afterConnectionEstablished(session, sessions);
        String userId = session.getAttributes().get("userId").toString();
        String imageId = session.getAttributes().get("imageId").toString();
        boolean broadcast = Boolean.parseBoolean(session.getAttributes().get("broadcast").toString());

        if(broadcast){
            ConcurrentWebSocketSessionDecorator sessionDecorator = new ConcurrentWebSocketSessionDecorator(session, 1000, 8192);
            sessionsBroadcast.put(userId+"/"+imageId, sessionDecorator);
        }

        log.debug("Established user position WebSocket connection {}", session.getId());
    }
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        super.afterConnectionClosed(session, status);

        if(Boolean.parseBoolean(session.getAttributes().get("broadcast").toString())){
            String userAndImageId = session.getAttributes().get("userId").toString() + '/' + session.getAttributes().get("imageId").toString();
            ConcurrentWebSocketSessionDecorator broadcastSession = sessionsBroadcast.get(userAndImageId);
            removeFromBroadcastSession(broadcastSession);
            removeFromTrackerSessions(broadcastSession);
        }
        else{
            removeSessionFromTrackerSessions(session);
        }
        removeFromSessions(session);
        log.debug("Closing user position WebSocket connection from {}", session.getRemoteAddress());
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        String broadcasterId = message.getPayload().toString();
        String followerId = session.getAttributes().get("userId").toString();
        String imageId = session.getAttributes().get("imageId").toString();

        ConcurrentWebSocketSessionDecorator broadcastSession = sessionsBroadcast.get(broadcasterId+"/"+imageId);
        ConcurrentWebSocketSessionDecorator followerSession = getSession(followerId, session.getId());

        if(followerSession != null){
            addToTrackedSessions(broadcastSession, followerSession);
        }

        moveFollowerAfterInitialConnection(Long.parseLong(broadcasterId), Long.parseLong(imageId), session);
    }

    private ConcurrentWebSocketSessionDecorator getSession(String followerId, String sessionId){
        ConcurrentWebSocketSessionDecorator followerSession = null;
        ConcurrentWebSocketSessionDecorator[] followerSessions = WebSocketUserPositionHandler.sessions.get(followerId);

        try {
            followerSession = Arrays.stream(followerSessions).filter(session -> session.getId().equals(sessionId)).toList().get(0);
        }catch (NullPointerException e){
            log.error("Follower : " + followerId + " has no session with id : " + sessionId);
        }
        return followerSession;
    }

    private void addToTrackedSessions(ConcurrentWebSocketSessionDecorator broadcastSession, ConcurrentWebSocketSessionDecorator trackingSession){
        if (WebSocketUserPositionHandler.sessionsTracked.containsKey(broadcastSession)) {
            ConcurrentWebSocketSessionDecorator[] trackedSessions = WebSocketUserPositionHandler.sessionsTracked.get(broadcastSession);

            boolean alreadyContainsSession = false;
            for (ConcurrentWebSocketSessionDecorator trackedSession : trackedSessions) {
                if (trackedSession.getId().equals(trackingSession.getId())) {
                    alreadyContainsSession = true;
                    break;
                }
            }

            if(!alreadyContainsSession){
                trackedSessions = addSession(trackedSessions, trackingSession);
            }

            WebSocketUserPositionHandler.sessionsTracked.replace(broadcastSession, WebSocketUserPositionHandler.sessionsTracked.get(broadcastSession), trackedSessions);
        } else {
            ConcurrentWebSocketSessionDecorator[] trackingSessions = new ConcurrentWebSocketSessionDecorator[]{trackingSession};
            WebSocketUserPositionHandler.sessionsTracked.put(broadcastSession, trackingSessions);
        }
    }

    private void moveFollowerAfterInitialConnection(Long userId, Long imageId, WebSocketSession session){

        // TODO : Uncomment to bypass authentication (websocket are not longer authenticated)
        // Comment for tests
        // ------------------------------------------------ //
        //List<GrantedAuthority> authorities = Arrays.asList(new SimpleGrantedAuthority("ROLE_ADMIN"));
        //UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken = new UsernamePasswordAuthenticationToken("admin", "adminPassword", authorities);
        //SecurityContextHolder.getContext().setAuthentication(usernamePasswordAuthenticationToken);
        // ------------------------------------------------ //

        ImageInstance imageInstance = imageInstanceService.get(imageId);
        SecUser secUser = secUserService.get(userId);
        Optional<LastUserPosition> lastPosition = userPositionService.lastPositionByUser(imageInstance, null, secUser, false);
        if(lastPosition.isPresent()){
            TextMessage position = new TextMessage(lastPosition.get().toJsonObject().toJsonString());
            sendPosition(session, position);
        }
    }

    public void sendPositionToFollowers(String userId, String imageId, String position) throws ServerException {
        String userAndImageId = userId+"/"+imageId;
        if(WebSocketUserPositionHandler.sessionsBroadcast.containsKey(userAndImageId)){
            ConcurrentWebSocketSessionDecorator broadcastSession = WebSocketUserPositionHandler.sessionsBroadcast.get(userAndImageId);
            ConcurrentWebSocketSessionDecorator[] sessions = WebSocketUserPositionHandler.sessionsTracked.get(broadcastSession);
            if(sessions != null){
                sendPosition(sessions, position);
            }
        }
    }

    private void sendPosition(ConcurrentWebSocketSessionDecorator[] sessions, String position){
        TextMessage message = new TextMessage(position);
        for(ConcurrentWebSocketSessionDecorator s : sessions){
            sendPosition(s, message);
        }
    }

    private void sendPosition(WebSocketSession session, TextMessage position){
        super.sendWebSocketMessage(session, position);
    }

    public List<String> getSessionsUserIds(ConcurrentWebSocketSessionDecorator[] sessions){
        List<String> userIds = new ArrayList<>();
        for(ConcurrentWebSocketSessionDecorator s : sessions){
            String userId = getSessionUserId(s);
            if(!userId.isEmpty()){
                userIds.add(userId);
            }
        }
        return userIds;
    }

    private String getSessionUserId(ConcurrentWebSocketSessionDecorator session){
        String userId = "";
        loop:
        for(Map.Entry<String, ConcurrentWebSocketSessionDecorator[]> entry : WebSocketUserPositionHandler.sessions.entrySet()){
            for(ConcurrentWebSocketSessionDecorator sessionDecorator : entry.getValue()){

                if(sessionDecorator.getId().equals(session.getId())){
                    System.out.println("Find user !");
                    userId = entry.getKey();
                    break loop;
                }

            }
        }
        return userId;
    }

    private void removeSessionFromTrackerSessions(WebSocketSession session) {
        log.debug("Remove this tracking session from tracked sessions");
        for (Map.Entry<ConcurrentWebSocketSessionDecorator, ConcurrentWebSocketSessionDecorator[]> entry : WebSocketUserPositionHandler.sessionsTracked.entrySet()) {

            for(ConcurrentWebSocketSessionDecorator trackedSession : entry.getValue()){
                if(trackedSession.getId().equals(session.getId())){
                    removeSessionFromTrackerSessions(session, entry.getKey());
                }
            }
        }
    }

    private void removeSessionFromTrackerSessions(WebSocketSession session, ConcurrentWebSocketSessionDecorator trackedSession){
        ConcurrentWebSocketSessionDecorator[] oldSessions = WebSocketUserPositionHandler.sessionsTracked.get(trackedSession);
        ConcurrentWebSocketSessionDecorator[] newSessions = removeSession(oldSessions, session);
        WebSocketUserPositionHandler.sessionsTracked.replace(trackedSession, oldSessions, newSessions);
    }

    private void removeFromSessions(WebSocketSession session) {
        for (Map.Entry<String, ConcurrentWebSocketSessionDecorator[]> entry : WebSocketUserPositionHandler.sessions.entrySet()) {

            for(ConcurrentWebSocketSessionDecorator trackedSession : entry.getValue()){
                if(trackedSession.getId().equals(session.getId())){
                    String userId = entry.getKey();
                    ConcurrentWebSocketSessionDecorator[] oldSessions = WebSocketUserPositionHandler.sessions.get(userId);
                    ConcurrentWebSocketSessionDecorator[] newSessions = removeSession(oldSessions, session);
                    WebSocketUserPositionHandler.sessions.replace(userId, oldSessions, newSessions);
                }
            }
        }
    }

    private void removeFromTrackerSessions(ConcurrentWebSocketSessionDecorator broadcastSession){
        log.debug("Remove this broadcast session from tracked sessions");
        ConcurrentWebSocketSessionDecorator[] sessionDecorators = WebSocketUserPositionHandler.sessionsTracked.get(broadcastSession);
        if(sessionDecorators != null){
            sendNotificationsAndCloseSessions(List.of(sessionDecorators));
        }
        WebSocketUserPositionHandler.sessionsTracked.remove(broadcastSession);
    }

    private void removeFromBroadcastSession(ConcurrentWebSocketSessionDecorator broadcastSession){
        log.debug("Remove this broadcast session from broadcast sessions");
        for(Map.Entry<String, ConcurrentWebSocketSessionDecorator> entry : WebSocketUserPositionHandler.sessionsBroadcast.entrySet()){
            if(entry.getValue().getId().equals(broadcastSession.getId())){
                WebSocketUserPositionHandler.sessionsBroadcast.remove(entry.getKey());
            }
        }
    }

    private ConcurrentWebSocketSessionDecorator[] removeSession(ConcurrentWebSocketSessionDecorator[] oldSessions, WebSocketSession oldSession){
        ConcurrentWebSocketSessionDecorator[] newSessions = new ConcurrentWebSocketSessionDecorator[oldSessions.length - 1];
        for(int i=0; i < oldSessions.length; i++){
            if(!oldSessions[i].getId().equals(oldSession.getId())){
                newSessions[i] = oldSessions[i];
            }
        }
        return newSessions;
    }

    private void sendNotificationsAndCloseSessions(List<ConcurrentWebSocketSessionDecorator> sessionDecorators){
        for(ConcurrentWebSocketSessionDecorator sessionDecorator : sessionDecorators){
            if(sessionDecorator.isOpen()){
                try{
                    sessionDecorator.sendMessage(new TextMessage("stop-track"));
                    afterConnectionClosed(sessionDecorator, CloseStatus.NORMAL);
                }catch (IOException e){
                    log.error("Failed to send 'stop-track' message to session : " + sessionDecorator.getId() + " " + e.getMessage());
                } catch (Exception e) {
                    log.error("Failed to close session : " + sessionDecorator.getId() + " " + e.getMessage());
                }
            }
        }
        // TODO : Send notification via future notification system (lvl importance of 2 to bypass queue)
        /*for (Map.Entry<Object, ConcurrentWebSocketSessionDecorator[]> entry : sessions.entrySet()) {
            for(ConcurrentWebSocketSessionDecorator s : entry.getValue()){
                if(decoratorsSession.contains(s)){
                }
            }
        }*/
    }
}

